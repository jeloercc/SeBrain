package com.sebrain.ingest

import java.io.File
import java.io.FileOutputStream

/**
 * Remembers which contents were already captured, as keyed tags.
 *
 * The file holds 32-byte HMAC tags made with K_dedup, never plain content hashes, so it cannot
 * be used to confirm whether a known text was captured without the Keystore key.
 */
class DedupIndex(private val file: File, private val tagger: (ByteArray) -> ByteArray) {
    private val tags: MutableSet<String> by lazy { load() }

    /** The tag of a content digest. This is a Keystore operation. */
    fun tagOf(contentSha256: ByteArray): ByteArray = tagger(contentSha256)

    @Synchronized
    fun contains(tag: ByteArray): Boolean = hex(tag) in tags

    /** Stores the tag durably before returning. */
    @Synchronized
    fun remember(tag: ByteArray) {
        require(tag.size == TAG_BYTES) { "Unexpected tag size" }
        if (!tags.add(hex(tag))) return
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { output ->
            output.write(tag)
            output.fd.sync()
        }
    }

    private fun load(): MutableSet<String> {
        val loaded = HashSet<String>()
        if (!file.exists()) return loaded
        val bytes = file.readBytes()
        // A torn last record, from a crash during an append, is ignored.
        for (offset in 0..bytes.size - TAG_BYTES step TAG_BYTES) {
            loaded += hex(bytes.copyOfRange(offset, offset + TAG_BYTES))
        }
        return loaded
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG_BYTES = 32
    }
}
