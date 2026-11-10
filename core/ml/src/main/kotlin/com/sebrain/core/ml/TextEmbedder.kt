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

    /** Embeds texts to be stored and searched later. */
    @Synchronized
    fun embedDocuments(texts: List<String>): List<FloatArray> = texts.map { embed("title: none | text: $it") }

    /** Embeds a search query. Queries and documents use different task prefixes. */
    @Synchronized
    fun embedQuery(query: String): FloatArray = embed("task: search result | query: $query")

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
                check(models.verify(model)) { "SHA-256 mismatch for ${model.fileName}" }
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
        const val TAG = "SEBRAIN_ML"
        const val DIMENSIONS = 256
        val OPTIONS = EmbeddingOptions(normalize = true, outputSize = DIMENSIONS)
    }
}
