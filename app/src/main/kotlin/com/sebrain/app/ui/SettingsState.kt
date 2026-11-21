package com.sebrain.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sebrain.app.SeBrainApp
import com.sebrain.core.crypto.InboxMode
import com.sebrain.core.net.TailnetGateway
import com.sebrain.ingest.InboxSettings

/**
 * The user's choices from Profile › Settings and the welcome flow. Plain preferences: they hold
 * choices, never note content. Each change takes effect at once.
 */
class SettingsState(private val app: SeBrainApp, private val gateway: TailnetGateway) {
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var welcomeSeen by mutableStateOf(prefs.getBoolean(KEY_WELCOME, false))
        private set
    var requireTailscale by mutableStateOf(prefs.getBoolean(KEY_TAILSCALE, false))
        private set
    var idleMinutes by mutableStateOf(idleMinutes(app))
        private set
    var inboxMode by mutableStateOf(InboxSettings.mode(app))
        private set

    /** Until when screenshots are allowed (epoch ms); 0 when they are blocked, the default. */
    var screenshotsUntil by mutableStateOf(prefs.getLong(KEY_SCREENSHOTS, 0L))
        private set

    fun screenshotsAllowed(now: Long = System.currentTimeMillis()): Boolean = now < screenshotsUntil

    /** Lifts the screenshot block for [SCREENSHOT_WINDOW_MS], or puts it back at once. */
    fun allowScreenshots(allowed: Boolean) {
        val until = if (allowed) System.currentTimeMillis() + SCREENSHOT_WINDOW_MS else 0L
        prefs.edit().putLong(KEY_SCREENSHOTS, until).apply()
        screenshotsUntil = until
    }

    init {
        gateway.requireTailscale = requireTailscale
    }

    fun finishWelcome() {
        prefs.edit().putBoolean(KEY_WELCOME, true).apply()
        welcomeSeen = true
    }

    /** From Profile › About: the logo and welcome steps show again. */
    fun replayWelcome() {
        prefs.edit().putBoolean(KEY_WELCOME, false).apply()
        welcomeSeen = false
    }

    /**
     * Off by default. When on and an AI provider is set up, general questions (not about the user)
     * go to the provider; only the typed message is sent, the user confirms each request, and
     * questions about the user, notes and memory never leave the phone.
     */
    var generalToProvider by mutableStateOf(prefs.getBoolean(KEY_GENERAL_PROVIDER, false))
        private set

    fun updateGeneralToProvider(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GENERAL_PROVIDER, enabled).apply()
        generalToProvider = enabled
    }

    fun updateRequireTailscale(required: Boolean) {
        prefs.edit().putBoolean(KEY_TAILSCALE, required).apply()
        gateway.requireTailscale = required
        requireTailscale = required
    }

    fun updateIdleMinutes(minutes: Int) {
        require(minutes in IDLE_CHOICES) { "Unsupported idle time: $minutes" }
        prefs.edit().putInt(KEY_IDLE, minutes).apply()
        app.session.idleTimeoutMs = minutes * MINUTE_MS
        idleMinutes = minutes
    }

    fun updateInboxMode(mode: InboxMode) {
        InboxSettings.setMode(app, mode)
        inboxMode = mode
    }

    companion object {
        private const val PREFS = "sebrain_settings"
        private const val KEY_WELCOME = "welcome_seen"
        private const val KEY_TAILSCALE = "require_tailscale"
        private const val KEY_IDLE = "idle_minutes"
        private const val KEY_SCREENSHOTS = "screenshots_until"
        private const val KEY_GENERAL_PROVIDER = "general_to_provider"
        private const val MINUTE_MS = 60_000L
        /** Screenshots come back off by themselves after this long. */
        const val SCREENSHOT_WINDOW_MS = 60 * MINUTE_MS
        const val DEFAULT_IDLE_MINUTES = 5
        val IDLE_CHOICES = listOf(1, 5, 15)

        /** Read by SeBrainApp before the vault session exists. */
        fun idleMinutes(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_IDLE, DEFAULT_IDLE_MINUTES)
                .takeIf { it in IDLE_CHOICES } ?: DEFAULT_IDLE_MINUTES

        fun idleTimeoutMs(context: Context): Long = idleMinutes(context) * MINUTE_MS
    }
}
