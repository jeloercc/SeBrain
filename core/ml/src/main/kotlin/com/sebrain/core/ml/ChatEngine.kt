package com.sebrain.core.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import java.util.concurrent.CountDownLatch

/**
 * Runs a language model the user installed, fully on the device.
 *
 * Backend chain: GPU, then CPU. Third-party models are not compiled for the Tensor NPU, and
 * `Backend` does not fall back by itself, so the chain is explicit. Not thread-safe: use it
 * from one thread.
 */
class ChatEngine(context: Context, private val models: ModelFiles) : AutoCloseable {
    private val appContext = context.applicationContext
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    var loaded: KnownModel? = null
        private set
    var backend: String? = null
        private set
    var supportsTools = false
        private set

    /** Installed models that are language models, as reported by the model files themselves. */
    fun languageModels(): List<KnownModel> = models.installed().filter { model ->
        runCatching { ModelInfo.from(models.file(model).path).use { it is ModelInfo.Llm } }
            .getOrElse { !model.fileName.contains("embedding", ignoreCase = true) }
    }

    /** Loads [model], releasing any other. A file is hashed the first time it is loaded. */
    fun load(model: KnownModel) {
        if (loaded == model && engine != null) return
        close()
        val path = models.file(model).path
        check(models.verifyOnce(model)) { "SHA-256 mismatch for ${model.fileName}" }
        supportsTools = runCatching {
            ModelInfo.from(path).use { (it as? ModelInfo.Llm)?.supportsFunctionCalling() == true }
        }.getOrDefault(false)

        var lastError: Throwable? = null
        for ((name, factory) in listOf<Pair<String, () -> Backend>>("gpu" to { Backend.GPU() }, "cpu" to { Backend.CPU() })) {
            try {
                engine = Engine(EngineConfig(modelPath = path, backend = factory(), cacheDir = appContext.cacheDir.path))
                    .also { it.initialize() }
                loaded = model
                backend = name
                Log.i(TAG, "language model loaded on $name, tools=$supportsTools")
                return
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "language model backend $name failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        throw IllegalStateException("No backend could load ${model.fileName}", lastError)
    }

    /** Starts a new conversation, forgetting the previous one. Tools are ignored if the model cannot call them. */
    fun startConversation(systemInstruction: String, tools: List<ToolSet> = emptyList()) {
        conversation?.close()
        val engine = checkNotNull(engine) { "No model is loaded" }
        val config = if (supportsTools && tools.isNotEmpty()) {
            ConversationConfig(systemInstruction = Contents.of(systemInstruction), tools = tools.map { tool(it) })
        } else {
            ConversationConfig(systemInstruction = Contents.of(systemInstruction))
        }
        conversation = engine.createConversation(config)
    }

    /** Sends a message and blocks until the reply is complete. [onText] receives the reply as it is written. */
    fun send(text: String, onText: (String) -> Unit): String {
        val conversation = checkNotNull(conversation) { "No conversation was started" }
        val done = CountDownLatch(1)
        val reply = StringBuilder()
        var failure: Throwable? = null
        conversation.sendMessageAsync(
            text,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    val piece = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    if (piece.isNotEmpty()) {
                        reply.append(piece)
                        onText(piece)
                    }
                }

                override fun onDone() = done.countDown()

                override fun onError(throwable: Throwable) {
                    failure = throwable
                    done.countDown()
                }
            },
        )
        done.await()
        failure?.let { throw it }
        return reply.toString()
    }

    /** Stops the reply being written, if any. Safe to call from another thread. */
    fun cancel() {
        runCatching { conversation?.cancelProcess() }
    }

    override fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        loaded = null
        backend = null
    }

    private companion object {
        const val TAG = "SEBRAIN_ML"
    }
}
