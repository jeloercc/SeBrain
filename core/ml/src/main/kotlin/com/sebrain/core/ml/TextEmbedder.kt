package com.sebrain.core.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData

/**
 * Text embeddings with EmbeddingGemma 2: 256 dimensions, L2 normalized.
 *
 * Backend chain: NPU, then CPU. The GPU is skipped on purpose: on the Pixel 10 it measured
 * slower than the CPU and lost two thirds of its speed when warm (docs/benchmarks.md).
 * `Backend.NPU` does not fall back by itself, so the chain is explicit.
 */
class TextEmbedder(context: Context, private val models: ModelFiles) : AutoCloseable {
    private val appContext = context.applicationContext
    private var engine: EmbeddingEngine? = null

    /** The backend in use, or null while no engine is loaded. */
    var backend: String? = null
        private set

    /** Whether a model file is installed. It does not load anything. */
    val isAvailable: Boolean
        get() = models.isInstalled(ModelFiles.EMBEDDING_NPU) || models.isInstalled(ModelFiles.EMBEDDING_CPU)

    /** Embeds texts to be stored and searched later, without a title. */
    @Synchronized
    fun embedDocuments(texts: List<String>): List<FloatArray> = embedTitledDocuments(texts.map { null to it })

    /**
     * Embeds documents with their titles, in the format EmbeddingGemma documents for retrieval:
     * `title: {title} | text: {content}`, or `title: none` when there is no title. Google's
     * guide states that including titles significantly improves retrieval accuracy.
     */
    @Synchronized
    fun embedTitledDocuments(documents: List<Pair<String?, String>>): List<FloatArray> =
        documents.map { (title, text) -> embed("title: ${documentTitle(title)} | text: $text") }

    /** Embeds a search query. Queries and documents use different task prefixes. */
    @Synchronized
    fun embedQuery(query: String): FloatArray = embed("task: search result | query: $query")

    /** A title as it goes in the prompt: on one line, without the separator the format uses. */
    private fun documentTitle(title: String?): String =
        title?.replace(Regex("\\s+"), " ")?.replace("|", "/")?.trim()?.takeIf { it.isNotEmpty() }?.take(TITLE_CHARS) ?: "none"

    /** Releases the model. The next call loads it again. */
    @Synchronized
    override fun close() {
        engine?.close()
        engine = null
        backend = null
    }

    private fun embed(text: String): FloatArray =
        engine().computeEmbedding(listOf(InputData.Text(text)), OPTIONS).embedding

    private fun engine(): EmbeddingEngine = engine ?: load().also { engine = it }

    private fun load(): EmbeddingEngine {
        val candidates = listOf(
            Triple("npu", ModelFiles.EMBEDDING_NPU) { Backend.NPU(appContext.applicationInfo.nativeLibraryDir) },
            Triple("cpu", ModelFiles.EMBEDDING_CPU) { Backend.CPU() },
        )
        var lastError: Throwable? = null
        for ((name, model, backendFactory) in candidates) {
            if (!models.isInstalled(model)) continue
            try {
                check(models.verifyOnce(model)) { "SHA-256 mismatch for ${model.fileName}" }
                val loaded = EmbeddingEngine(
                    EmbeddingEngineConfig(
                        modelPath = models.file(model).path,
                        backend = backendFactory(),
                        cacheDir = appContext.cacheDir.path,
                    ),
                ).also { it.initialize() }
                backend = name
                Log.i(TAG, "embedding engine loaded on $name")
                return loaded
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "embedding backend $name failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        throw IllegalStateException("No embedding backend is available", lastError)
    }

    private companion object {
        const val TITLE_CHARS = 120
        const val TAG = "SEBRAIN_ML"
        const val DIMENSIONS = 256
        val OPTIONS = EmbeddingOptions(normalize = true, outputSize = DIMENSIONS)
    }
}
