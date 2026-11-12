package com.sebrain.core.net

import java.io.File
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
     * A mismatch deletes the file and fails. [onProgress] receives bytes done and total.
     */
    fun download(file: HubFile, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val partial = File(target.path + ".part")
        target.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = gateway.openFollowingRedirects(URL("$HOST/${file.repo}/resolve/main/${file.path}"))
        try {
            if (connection.responseCode != 200) throw IOException("Download failed: HTTP ${connection.responseCode}")
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, file.sizeBytes)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != file.sha256) throw IOException("SHA-256 mismatch: the download was discarded")
            if (!partial.renameTo(target)) throw IOException("Could not store ${target.name}")
        } finally {
            connection.disconnect()
            partial.delete()
        }
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
