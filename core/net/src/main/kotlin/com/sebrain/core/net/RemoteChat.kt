package com.sebrain.core.net

import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

/**
 * A language model reached over an API in the OpenAI chat format, which most providers accept:
 * OpenRouter, OpenAI, Anthropic, Gemini, Groq, and servers such as Ollama or LM Studio.
 * [baseUrl] is the part before `/chat/completions`.
 */
data class RemoteProvider(val id: String, val name: String, val baseUrl: String, val model: String)

/** One message of the conversation sent to the provider: `system`, `user` or `assistant`. */
data class RemoteMessage(val role: String, val content: String)

/** The provider answered with an error, such as a wrong key or no credit left. */
class RemoteChatException(message: String) : IOException(message)

/**
 * Sends a conversation to a [RemoteProvider] and streams the reply. Every connection goes
 * through [TailnetGateway], so it is HTTPS only, like the rest of the app. No SDK is used: the
 * request is one JSON body and the reply a stream of server-sent events.
 *
 * Only what the caller passes in [reply] leaves the phone. Deciding what that is, and showing it
 * to the user first, is the caller's job.
 */
class RemoteChat(private val gateway: TailnetGateway) {
    @Volatile private var active: HttpsURLConnection? = null
    @Volatile private var cancelled = false

    /**
     * Sends [messages] and blocks until the reply is complete or [cancel] is called; [onText]
     * receives the reply as it is written. Throws [RemoteChatException] when the provider refuses.
     */
    fun reply(provider: RemoteProvider, apiKey: String, messages: List<RemoteMessage>, maxTokens: Int, onText: (String) -> Unit): String {
        cancelled = false
        val connection = gateway.open(URL(endpoint(provider.baseUrl)))
        active = connection
        val reply = StringBuilder()
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.outputStream.use { it.write(requestBody(provider.model, messages, maxTokens).toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                throw RemoteChatException(errorMessage(code, body))
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                while (!cancelled) {
                    val line = input.readLine() ?: break
                    when (val event = parseEvent(line)) {
                        is Event.Text -> {
                            reply.append(event.text)
                            onText(event.text)
                        }
                        is Event.Failure -> throw RemoteChatException(event.message)
                        Event.Done -> break
                        Event.Nothing -> Unit
                    }
                }
            }
        } catch (e: IOException) {
            // A cancel closes the connection under the reader; what arrived until then is kept.
            if (!cancelled) throw e
        } finally {
            active = null
            connection.disconnect()
        }
        return reply.toString()
    }

    /** Stops the reply being written, if any. Safe to call from another thread. */
    fun cancel() {
        cancelled = true
        runCatching { active?.disconnect() }
    }

    sealed class Event {
        data class Text(val text: String) : Event()
        data class Failure(val message: String) : Event()
        data object Done : Event()
        data object Nothing : Event()
    }

    companion object {
        const val READ_TIMEOUT_MS = 120_000

        fun endpoint(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            require(base.startsWith("https://")) { "The provider's address must start with https://" }
            return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }

        fun requestBody(model: String, messages: List<RemoteMessage>, maxTokens: Int): String = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray(messages.map { JSONObject().put("role", it.role).put("content", it.content) }))
            .toString()

        /** One line of the event stream. Comments (`: keep-alive`) and blank lines are nothing. */
        fun parseEvent(line: String): Event {
            if (!line.startsWith("data:")) return Event.Nothing
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) return Event.Nothing
            if (data == "[DONE]") return Event.Done
            val json = runCatching { JSONObject(data) }.getOrNull() ?: return Event.Nothing
            json.optJSONObject("error")?.let { return Event.Failure(it.optString("message").ifEmpty { "The provider reported an error" }) }
            val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return Event.Nothing
            choice.optJSONObject("error")?.let { return Event.Failure(it.optString("message").ifEmpty { "The provider reported an error" }) }
            val content = choice.optJSONObject("delta")?.let { if (it.isNull("content")) null else it.optString("content") }
                ?: choice.optJSONObject("message")?.let { if (it.isNull("content")) null else it.optString("content") }
            return if (content.isNullOrEmpty()) Event.Nothing else Event.Text(content)
        }

        /** A short reason for an HTTP error, from the provider's JSON when it sends one. */
        fun errorMessage(code: Int, body: String): String {
            val detail = runCatching {
                val json = JSONObject(body)
                json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: json.optString("message").takeIf { it.isNotBlank() }
            }.getOrNull()
            val hint = when (code) {
                401, 403 -> "the API key was refused"
                402 -> "the account has no credit left"
                404 -> "the address or the model name is wrong"
                429 -> "too many requests; try again shortly"
                in 500..599 -> "the provider failed"
                else -> "HTTP $code"
            }
            return if (detail != null) "$hint ($detail)" else hint
        }
    }
}
