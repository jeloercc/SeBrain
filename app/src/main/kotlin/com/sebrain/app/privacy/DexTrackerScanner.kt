package com.sebrain.app.privacy

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Finds tracker SDKs inside an APK without running it. Every class in a dex file is named by
 * a type descriptor such as `Lcom/appsflyer/AppsFlyerLib;`, so a tracker whose package is
 * `com.appsflyer` shows up as the bytes `Lcom/appsflyer/`. The dex files are read in fixed
 * blocks, so memory stays bounded whatever the APK size.
 */
class DexTrackerScanner(trackers: List<Tracker> = TrackerSignatures.all) {
    private class Pattern(val bytes: ByteArray, val tracker: Tracker)

    /** Patterns grouped by the two bytes after the leading `L`, to skip most positions fast. */
    private val byPrefix: Map<Int, List<Pattern>>
    private val maxLength: Int

    init {
        val patterns = trackers.flatMap { tracker ->
            tracker.packages.map { Pattern(descriptor(it), tracker) }
        }
        byPrefix = patterns.groupBy { key(it.bytes[1], it.bytes[2]) }
        maxLength = patterns.maxOf { it.bytes.size }
    }

    /** Scans the base APK and its splits. Unreadable files are skipped. */
    fun scanApks(paths: List<String>): Set<Tracker> {
        val found = HashSet<Tracker>()
        for (path in paths) {
            val file = File(path)
            if (!file.canRead()) continue
            runCatching {
                ZipFile(file).use { zip ->
                    for (entry in zip.entries()) {
                        if (DEX_NAME.matches(entry.name)) zip.getInputStream(entry).use { scan(it, found) }
                    }
                }
            }
        }
        return found
    }

    /** Adds every tracker found in [input] to [found]. */
    fun scan(input: InputStream, found: MutableSet<Tracker> = HashSet()): Set<Tracker> {
        // The last maxLength - 1 bytes of each block are carried over, so a match that crosses
        // a block boundary is still seen.
        val carry = maxLength - 1
        val buffer = ByteArray(BLOCK + carry)
        var kept = 0
        while (true) {
            val read = input.readNBytes(buffer, kept, BLOCK)
            val atEnd = read < BLOCK
            val end = kept + maxOf(read, 0)
            val last = if (atEnd) end else end - carry
            for (i in 0 until last) {
                if (buffer[i] != 'L'.code.toByte() || i + 2 >= end) continue
                val candidates = byPrefix[key(buffer[i + 1], buffer[i + 2])] ?: continue
                for (pattern in candidates) {
                    if (pattern.tracker !in found && matchesAt(buffer, i, end, pattern.bytes)) {
                        found += pattern.tracker
                    }
                }
            }
            if (atEnd) break
            buffer.copyInto(buffer, 0, last, end)
            kept = end - last
        }
        return found
    }

    private fun matchesAt(buffer: ByteArray, at: Int, end: Int, pattern: ByteArray): Boolean {
        if (at + pattern.size > end) return false
        for (j in pattern.indices) if (buffer[at + j] != pattern[j]) return false
        return true
    }

    private fun key(first: Byte, second: Byte) = (first.toInt() and 0xff shl 8) or (second.toInt() and 0xff)

    companion object {
        private const val BLOCK = 1 shl 20
        private val DEX_NAME = Regex("classes\\d*\\.dex")

        /** `com.appsflyer` → `Lcom/appsflyer/`, the start of every class descriptor in it. */
        fun descriptor(javaPackage: String): ByteArray =
            ("L" + javaPackage.replace('.', '/') + "/").toByteArray(Charsets.US_ASCII)
    }
}
