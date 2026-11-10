package com.sebrain.core.ml

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** A model file the app accepts, identified by its pinned SHA-256. The app downloads nothing. */
data class KnownModel(val fileName: String, val sha256: String, val description: String)

/** Model files imported by the user. A file is only accepted if its hash is pinned here. */
class ModelFiles(private val dir: File) {
    fun file(model: KnownModel): File = File(dir, model.fileName)

    fun isInstalled(model: KnownModel): Boolean = file(model).exists()

    /** Checks the installed file against its pinned hash. */
    fun verify(model: KnownModel): Boolean = file(model).let { it.exists() && sha256(it.inputStream()) == model.sha256 }

    /**
     * Copies [input] into the models directory if its hash matches a known model.
     * Returns the model, or null if the content is not a known model; nothing is kept then.
     */
    fun import(input: InputStream): KnownModel? {
        dir.mkdirs()
        val temporary = File(dir, "import.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { source ->
            temporary.outputStream().use { target ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    target.write(buffer, 0, read)
                }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val model = ALL.firstOrNull { it.sha256 == hash }
        if (model == null || !temporary.renameTo(file(model))) {
            temporary.delete()
            return null
        }
        return model
    }

    companion object {
        // litert-community/embeddinggemma-2-text-270m-litert-lm at revision 9be6e8b9 (Apache-2.0).
        val EMBEDDING_NPU = KnownModel(
            "embeddinggemma-2-text-270m_Google_Tensor_G5.litertlm",
            "f066663433a31a8228b46f3271d2714a3b72a341ed4563cbc6d90f2e3dd0162d",
            "EmbeddingGemma 2 Text 270M for the Tensor G5 NPU",
        )
        val EMBEDDING_CPU = KnownModel(
            "embeddinggemma-2-text-270m.litertlm",
            "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb",
            "EmbeddingGemma 2 Text 270M for CPU",
        )
        val ALL = listOf(EMBEDDING_NPU, EMBEDDING_CPU)

        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            input.use { source ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
