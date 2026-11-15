package com.sebrain.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sebrain.core.net.RemoteProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The provider store on its own file and Keystore key, so the user's providers are not touched. */
@RunWith(AndroidJUnit4::class)
class ProviderStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "providers-test-${System.nanoTime()}.bin"
    private val store = ProviderStore(context, name, "test_providers_${System.nanoTime()}")

    @After
    fun tearDown() = store.deleteEverything()

    @Test
    fun aProviderIsStoredWithItsKeyAndTheKeyIsNotOnDiskInPlainText() {
        val key = "sk-or-v1-0123456789abcdef-secret"
        val saved = store.save(RemoteProvider("", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-5.2"), key)
        assertTrue(saved.id.isNotEmpty())
        assertEquals(listOf(saved), ProviderStore(context, name, store.alias).list())
        assertEquals(key, store.apiKey(saved.id))
        val bytes = File(context.noBackupFilesDir, name).readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("secret"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("openrouter"))
    }

    @Test
    fun aBlankKeyKeepsTheStoredOneAndRemovingDeletesIt() {
        val saved = store.save(RemoteProvider("", "Groq", "https://api.groq.com/openai/v1", "llama"), "key-1")
        store.save(saved.copy(model = "llama-2"), "")
        assertEquals("key-1", store.apiKey(saved.id))
        assertEquals("llama-2", store.list().single().model)
        store.remove(saved.id)
        assertTrue(store.list().isEmpty())
        assertNull(store.apiKey(saved.id))
    }

    @Test
    fun anAddressThatIsNotHttpsOrAProviderWithoutKeyIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { store.save(RemoteProvider("", "Local", "http://192.168.1.2:11434/v1", "m"), "k") }
        assertThrows(IllegalArgumentException::class.java) { store.save(RemoteProvider("", "OpenAI", "https://api.openai.com/v1", "m"), " ") }
    }
}
