package com.sebrain.core.net

import com.sebrain.core.net.RemoteChat.Event
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChatTest {
    @Test
    fun theEndpointIsBuiltFromTheBaseAddressAndMustBeHttps() {
        assertEquals("https://openrouter.ai/api/v1/chat/completions", RemoteChat.endpoint("https://openrouter.ai/api/v1/"))
        assertEquals("https://api.anthropic.com/v1/chat/completions", RemoteChat.endpoint(" https://api.anthropic.com/v1 "))
        assertEquals("https://x.ts.net/v1/chat/completions", RemoteChat.endpoint("https://x.ts.net/v1/chat/completions"))
        assertThrows(IllegalArgumentException::class.java) { RemoteChat.endpoint("http://localhost:11434/v1") }
    }

    @Test
    fun theRequestCarriesTheModelTheMessagesAndTheLimit() {
        val body = JSONObject(RemoteChat.requestBody("openai/gpt-5.2", listOf(RemoteMessage("system", "Be brief."), RemoteMessage("user", "Hola \"mundo\"")), 900))
        assertEquals("openai/gpt-5.2", body.getString("model"))
        assertEquals(true, body.getBoolean("stream"))
        assertEquals(900, body.getInt("max_tokens"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("Hola \"mundo\"", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun streamEventsAreReadAsTextDoneOrNothing() {
        assertEquals(Event.Text("Hola"), RemoteChat.parseEvent("""data: {"choices":[{"delta":{"content":"Hola"}}]}"""))
        assertEquals(Event.Done, RemoteChat.parseEvent("data: [DONE]"))
        assertEquals(Event.Nothing, RemoteChat.parseEvent(": OPENROUTER PROCESSING"))
        assertEquals(Event.Nothing, RemoteChat.parseEvent(""))
        assertEquals(Event.Nothing, RemoteChat.parseEvent("""data: {"choices":[{"delta":{"role":"assistant","content":null}}]}"""))
        assertEquals(Event.Nothing, RemoteChat.parseEvent("""data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"total_tokens":14}}"""))
        // A provider that ignores "stream" and answers in one piece is read too.
        assertEquals(Event.Text("Entero"), RemoteChat.parseEvent("""data: {"choices":[{"message":{"content":"Entero"}}]}"""))
    }

    @Test
    fun anErrorInTheStreamOrInTheResponseIsReported() {
        assertEquals(Event.Failure("Rate limited"), RemoteChat.parseEvent("""data: {"error":{"code":429,"message":"Rate limited"}}"""))
        assertEquals(Event.Failure("Provider down"), RemoteChat.parseEvent("""data: {"choices":[{"error":{"message":"Provider down"}}]}"""))
        assertEquals("the API key was refused (No auth credentials found)", RemoteChat.errorMessage(401, """{"error":{"message":"No auth credentials found","code":401}}"""))
        assertEquals("the account has no credit left", RemoteChat.errorMessage(402, "not json"))
        assertTrue(RemoteChat.errorMessage(418, "").contains("418"))
    }
}
