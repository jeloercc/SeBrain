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

    fun sha256Hex(canonical: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * Digests of recent captures, in memory only: a content hash on disk would let anyone
 * confirm whether a known text was captured. The digest also travels inside the encrypted
 * segment, so cognition can deduplicate against the vault.
 */
internal class RecentDigests(private val capacity: Int = 512) {
    private val digests = object : LinkedHashMap<String, Unit>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > capacity
    }

    /** Returns true if the digest was not seen before, and remembers it. */
    @Synchronized
    fun add(digest: String): Boolean = digests.put(digest, Unit) == null

    @Synchronized
    fun remove(digest: String) {
        digests.remove(digest)
    }
}
