package com.sebrain.ingest

import java.security.MessageDigest
import java.text.Normalizer

/** Produces the canonical text used for storage and deduplication. */
internal object CaptureNormalizer {
    private val TRAILING_SPACES = Regex("[ \\t]+(?=\\n)")

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\u0000", "")
            .replace(TRAILING_SPACES, "")
            .trim()
            .let(LinkCleaner::clean)

    fun sha256(canonical: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
}
