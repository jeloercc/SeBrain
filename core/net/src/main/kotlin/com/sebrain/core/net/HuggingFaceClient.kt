package com.sebrain.core.net

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class HubModel(val repo: String, val downloads: Long, val likes: Long, val gated: Boolean)

data class HubFile(val repo: String, val path: String, val sizeBytes: Long, val sha256: String) {
    val fileName: String get() = path.substringAfterLast('/')
}

/** Searches and downloads LiteRT-LM models from Hugging Face. */
class HuggingFaceClient(private val gateway: TailnetGateway) {
    fun search(words: String): List<HubModel> {
        val query = URLEncoder.encode("$words litertlm".trim(), "UTF-8")
        return parseModels(get("$API?search=$query&sort=downloads&direction=-1&limit=30"))
    }

    /** The model files of a repository, with the SHA-256 that Hugging Face publishes for each. */
    fun files(repo: String): List<HubFile> = parseFiles(repo, get("$API/$repo/tree/main"))

    /**
     * Downloads [file] into [target] and checks it against the published SHA-256.
     *
     * A dropped connection is retried, continuing from the last byte written (an HTTP range
     * request), and the partial file is kept for a later call to continue too. One download per
     * file at a time ([LOCK]). Before the file takes [target]'s place its hash is taken again from
     * what is on disk, so a bad write is never installed; on a mismatch the partial file is deleted.
     * [onProgress] receives bytes done and total.
     */
    fun download(file: HubFile, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) = synchronized(LOCK) {
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        if (partial.length() > file.sizeBytes) partial.delete()
        var attempt = 0
        while (partial.length() < file.sizeBytes) {
            try {
                fetchRest(file, partial, onProgress)
            } catch (e: IOException) {
                attempt++
                if (attempt >= MAX_ATTEMPTS) throw IOException("Download interrupted (${e.message}); it continues from ${partial.length() * 100 / file.sizeBytes}% next time", e)
                Thread.sleep(RETRY_DELAY_MS * attempt)
            }
        }
        if (sha256(partial) != file.sha256) {
            partial.delete()
            throw IOException("SHA-256 mismatch: the download was discarded")
        }
        if (!partial.renameTo(target)) throw IOException("Could not store ${target.name}")
    }

    /** Appends what is missing of [file] to [partial], asking only for the bytes after its end. */
    private fun fetchRest(file: HubFile, partial: File, onProgress: (Long, Long) -> Unit) {
        val start = partial.length()
        val headers = if (start > 0) mapOf("Range" to "bytes=$start-") else emptyMap()
        val connection = gateway.openFollowingRedirects(URL("$HOST/${file.repo}/resolve/main/${file.path}"), headers)
        try {
            // 206: the rest from [start]. 200: the server sent the whole file, so start over.
            val append = when (connection.responseCode) {
                206 -> true
                200 -> false
                else -> throw IOException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var done = if (append) start else 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, file.sizeBytes)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun get(url: String): String {
        val connection = gateway.openFollowingRedirects(URL(url))
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val HOST = "https://huggingface.co"
        private const val API = "$HOST/api/models"
        private val MODEL_EXTENSIONS = listOf(".litertlm")
        /** Tries per call before giving up; the partial file stays for the next call. */
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 3_000L
        /** One download writes a given partial file at a time, across every client. */
        private val LOCK = Any()

        internal fun parseModels(json: String): List<HubModel> {
            val array = JSONArray(json)
            return (0 until array.length()).map { array.getJSONObject(it) }.map { m ->
                HubModel(m.getString("id"), m.optLong("downloads"), m.optLong("likes"), isGated(m))
            }
        }

        internal fun parseFiles(repo: String, json: String): List<HubFile> {
            val array = JSONArray(json)
            return (0 until array.length()).map { array.getJSONObject(it) }.mapNotNull { f ->
                val path = f.getString("path")
                val hash = f.optJSONObject("lfs")?.optString("oid").orEmpty()
                if (MODEL_EXTENSIONS.none { path.endsWith(it) } || hash.length != 64) null
                else HubFile(repo, path, f.optLong("size"), hash)
            }
        }

        private fun isGated(model: JSONObject): Boolean = when (val gated = model.opt("gated")) {
            null, JSONObject.NULL, false -> false
            else -> gated != "false"
        }
    }
}
