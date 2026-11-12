package com.sebrain.core.vault

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.sebrain.core.crypto.VaultKeyException
import com.sebrain.core.crypto.WrappedDekStore
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException

/** The session is closing, so it does not accept new work. */
class VaultSessionClosingException : IllegalStateException("The vault session is closing")

/**
 * Opens the vault on demand. It closes after the idle timeout and when the screen turns off,
 * and it is only usable while the screen is on and the device is unlocked. Apps cannot observe
 * the lock itself, and the keyguard can lock some seconds after the screen turns off, so the
 * screen state is the boundary: otherwise a session opened in that gap would stay open locked.
 *
 * The raw key stays in memory while the database is open: sqlcipher-android keeps a reference
 * to the key array for later connections, so it can only be zeroed after close. See
 * docs/design-vault-session.md.
 */
class VaultSession(
    context: Context,
    private val databaseFile: File,
    private val dekStore: WrappedDekStore,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val keyguard = appContext.getSystemService(KeyguardManager::class.java)
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val lock = ReentrantLock()
    private val handler = Handler(Looper.getMainLooper())
    private val idleCheck = Runnable { onIdleCheck() }
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = requestClose()
    }

    private var database: SQLiteDatabase? = null
    private var rawKey: ByteArray? = null
    private var activeUses = 0
    private var closePending = false
    private var lastUseMs = 0L
    private var receiverRegistered = false

    /** The key array of the last closed database, kept only so tests can check it was zeroed. */
    internal var lastClosedKey: ByteArray? = null
        private set

    val isOpen: Boolean
        get() = lock.withLock { database != null }

    /** Runs [block] with the open database, opening it first if needed. */
    fun <T> withDatabase(block: (SQLiteDatabase) -> T): T {
        val db = lock.withLock {
            if (closePending) throw VaultSessionClosingException()
            val refusal = when {
                keyguard.isDeviceLocked -> VaultKeyException.DeviceLocked()
                !power.isInteractive -> VaultKeyException.ScreenOff()
                else -> null
            }
            if (refusal != null) {
                // Also covers a missed screen-off broadcast.
                if (database != null) {
                    if (activeUses > 0) closePending = true else closeLocked()
                }
                throw refusal
            }
            if (database != null && activeUses == 0 && isExpired()) closeLocked()
            val current = database ?: openLocked()
            activeUses++
            handler.removeCallbacks(idleCheck)
            current
        }
        try {
            return block(db)
        } finally {
            lock.withLock {
                activeUses--
                lastUseMs = SystemClock.elapsedRealtime()
                if (activeUses == 0) {
                    if (closePending) closeLocked() else scheduleIdleCheck(idleTimeoutMs)
                }
            }
        }
    }

    /** Closes the database now, or as soon as the work in progress ends. */
    override fun close() = requestClose()

    private fun requestClose() = lock.withLock {
        if (database == null) return@withLock
        if (activeUses > 0) closePending = true else closeLocked()
    }

    private fun openLocked(): SQLiteDatabase {
        VaultNative.load()
        val dek = when {
            dekStore.exists() -> dekStore.unwrap()
            databaseFile.exists() -> throw VaultKeyException.WrappedDekMissing(dekStore.blobFile.path)
            else -> dekStore.createAndStore()
        }
        val key = try {
            SqlcipherRawKey.encode(dek)
        } finally {
            dek.fill(0)
        }
        val db = try {
            databaseFile.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(databaseFile, key, null, null).also { opened ->
                try {
                    VaultSchema.migrate(opened)
                } catch (e: Throwable) {
                    opened.close()
                    throw e
                }
            }
        } catch (e: SQLiteNotADatabaseException) {
            key.fill(0)
            throw VaultKeyException.KeyMismatch(e)
        } catch (e: Throwable) {
            key.fill(0)
            throw e
        }
        database = db
        rawKey = key
        lastUseMs = SystemClock.elapsedRealtime()
        appContext.registerReceiver(
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            Context.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        return db
    }

    private fun closeLocked() {
        handler.removeCallbacks(idleCheck)
        if (receiverRegistered) {
            appContext.unregisterReceiver(screenOffReceiver)
            receiverRegistered = false
        }
        try {
            database?.close()
        } finally {
            rawKey?.let {
                it.fill(0)
                lastClosedKey = it
            }
            database = null
            rawKey = null
            closePending = false
        }
    }

    private fun isExpired(): Boolean = SystemClock.elapsedRealtime() - lastUseMs >= idleTimeoutMs

    private fun scheduleIdleCheck(delayMs: Long) {
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, delayMs)
    }

    private fun onIdleCheck() = lock.withLock {
        if (database == null || activeUses > 0) return@withLock
        val remaining = idleTimeoutMs - (SystemClock.elapsedRealtime() - lastUseMs)
        if (remaining <= 0) closeLocked() else scheduleIdleCheck(remaining)
    }

    companion object {
        /** Proposed value: long enough for gaps between short operations, short enough to limit exposure. */
        const val DEFAULT_IDLE_TIMEOUT_MS = 5L * 60L * 1000L
    }
}
