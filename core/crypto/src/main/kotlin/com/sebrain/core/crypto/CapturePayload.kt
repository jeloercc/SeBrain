package com.sebrain.core.crypto

import org.json.JSONException
import org.json.JSONObject

/** The plaintext of an inbox segment. It only exists encrypted on disk. */
data class CapturePayload(
    val capturedAt: String,
    val timeZone: String,
    val source: String,
    val contentSha256: String,
    val subject: String?,
    val text: String,
) {
    fun encode(): ByteArray = JSONObject()
        .put("schema", SCHEMA)
        .put("captured_at", capturedAt)
        .put("time_zone", timeZone)
        .put("source", source)
        .put("content_sha256", contentSha256)
        .put("subject", subject ?: JSONObject.NULL)
        .put("text", text)
        .toString()
        .toByteArray(Charsets.UTF_8)

    companion object {
        const val SCHEMA = 1

        /** @throws IllegalArgumentException if the bytes are not a supported payload. */
        fun decode(bytes: ByteArray): CapturePayload = try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            require(json.getInt("schema") == SCHEMA) { "Unsupported payload schema" }
            CapturePayload(
                capturedAt = json.getString("captured_at"),
                timeZone = json.getString("time_zone"),
                source = json.getString("source"),
                contentSha256 = json.getString("content_sha256"),
                subject = if (json.isNull("subject")) null else json.getString("subject"),
                text = json.getString("text"),
            )
        } catch (e: JSONException) {
            throw IllegalArgumentException("Malformed payload", e)
        }
    }
}
