package com.sebrain.app.ui

import android.content.ClipDescription
import android.os.PersistableBundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Keeps what the user types and copies out of other apps' memory, for every text field and
 * every selection below it:
 * - the keyboard is asked not to learn from it (IME_FLAG_NO_PERSONALIZED_LEARNING; Gboard
 *   honours it, other keyboards may not);
 * - copied text is marked sensitive, so Android does not show it in the clipboard preview.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PrivateInput(content: @Composable () -> Unit) {
    val base = LocalClipboard.current
    val clipboard = remember(base) { SensitiveClipboard(base) }
    CompositionLocalProvider(LocalClipboard provides clipboard) {
        InterceptPlatformTextInput(
            interceptor = { request, nextHandler ->
                nextHandler.startInputMethod(NoLearningRequest(request))
            },
            content = content,
        )
    }
}

private class NoLearningRequest(private val request: PlatformTextInputMethodRequest) : PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
        val connection = request.createInputConnection(outAttributes)
        // Set after the field fills in its own options, so nothing overwrites it.
        outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return connection
    }
}

private class SensitiveClipboard(private val base: Clipboard) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = base.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        // The ClipData was just built for this copy; flag it before it leaves the app.
        clipEntry?.clipData?.description?.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        base.setClipEntry(clipEntry)
    }

    override val nativeClipboard get() = base.nativeClipboard
}
