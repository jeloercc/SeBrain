package com.sebrain.ingest

import android.content.Context
import com.sebrain.core.crypto.InboxMode

/**
 * Which K_inbox new shares are encrypted to. Kept in plain preferences: it is a choice, not content.
 * Segments already in the inbox keep the key id they were written with, so changing it loses nothing.
 */
object InboxSettings {
    private const val PREFS = "sebrain_inbox"
    private const val KEY_MODE = "mode"

    fun mode(context: Context): InboxMode =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
            ?.let { name -> InboxMode.entries.firstOrNull { it.name == name } }
            ?: InboxMode.DEFAULT

    fun setMode(context: Context, mode: InboxMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.name).apply()
        ShareActivity.resetIngestor()
    }
}
