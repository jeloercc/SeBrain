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
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs a language model the user installed, fully on the device.
 *
 * Backend chain: a build compiled for the Tensor G5 runs on the NPU only; any other model runs on
 * the GPU, then the CPU. `Backend` does not fall back by itself, so the chain is explicit. Not thread-safe: use it
 * from one thread.
 */
@OptIn(ExperimentalApi::class)
class ChatEngine(
    context: Context,
    private val models: ModelFiles,
    /** Collects speed figures for [lastStats] and the log: token counts and tok/s, never text. */
    private val benchmark: Boolean = false,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private var engine: Engine? = null

    var loaded: KnownModel? = null
        private set
    var backend: String? = null
        private set

    /** Installed models that are language models, as reported by the model files themselves. */
    fun languageModels(): List<KnownModel> = models.installed().filter { model ->
        runCatching { ModelInfo.from(models.file(model).path).use { it is ModelInfo.Llm } }
            .getOrElse { !model.fileName.contains("embedding", ignoreCase = true) }
    }

    /**
     * A file that does not match its pinned SHA-256 is never loaded. It is removed, so the app
     * does not stay stuck on it, and the log says what was found: hashes and size, never content.
     */
    private fun reject(model: KnownModel): Nothing {
        val file = models.file(model)
        val actual = if (file.exists()) runCatching { ModelFiles.sha256(file.inputStream()) }.getOrNull() else null
        Log.w(TAG, "model rejected: ${model.fileName} exists=${file.exists()} size=${file.length()} expected=${model.sha256.take(12)} actual=${actual?.take(12)}")
        models.remove(model)
        throw IllegalStateException(
            "${model.description} did not match its SHA-256 fingerprint, so the file was removed. " +
                "Download it again in Profile › Device & models.",
        )
    }

    /** Loads [model], releasing any other. A file is hashed the first time it is loaded. */
    fun load(model: KnownModel) {
        if (loaded == model && engine != null) return
        close()
        val path = models.file(model).path
        if (!models.verifyOnce(model)) reject(model)
        if (benchmark) ExperimentalFlags.enableBenchmark = true
        var lastError: Throwable? = null
        for ((name, factory) in backends(model)) {
            try {
                engine = Engine(EngineConfig(modelPath = path, backend = factory(), cacheDir = appContext.cacheDir.path))
                    .also { it.initialize() }
                loaded = model
                backend = name
                Log.i(TAG, "language model loaded on $name")
                return
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "language model backend $name failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        throw IllegalStateException("No backend could load ${model.fileName}", lastError)
    }

    private fun backends(model: KnownModel): List<Pair<String, () -> Backend>> =
        if (ModelFiles.compiledForTensorNpu(model)) {
            listOf("npu" to { Backend.NPU(appContext.applicationInfo.nativeLibraryDir) })
        } else {
            listOf("gpu" to { Backend.GPU() }, "cpu" to { Backend.CPU() })
        }

    @Volatile private var active: Conversation? = null
    @Volatile private var cancelled = false
    @Volatile private var looped = false

    /** True if the last reply was cut because the model had started to repeat itself. */
    @Volatile var lastLooped = false
        private set

    /**
     * What the last reply cost. [contextTokens] is everything in the conversation when it ended:
     * system instruction, history, prompt and reply. A field the engine did not report is zero.
     */
    class Stats(
        val contextTokens: Int,
        val promptTokens: Int,
        val replyTokens: Int,
        val promptTokensPerSecond: Double,
        val replyTokensPerSecond: Double,
        val firstTokenSeconds: Double,
    ) {
        override fun toString() =
            "context=$contextTokens prompt=$promptTokens reply=$replyTokens " +
                "prefill=%.0f tok/s decode=%.1f tok/s first=%.2f s".format(promptTokensPerSecond, replyTokensPerSecond, firstTokenSeconds)
    }

    /** Statistics of the last finished reply, or null if they could not be read. */
    @Volatile var lastStats: Stats? = null
        private set

    /** One earlier message of the conversation, given back to the model as history. */
    class Turn(val fromUser: Boolean, val text: String)

    /**
     * Answers [text] in a fresh conversation that starts from [history]. Blocks until the reply
     * is complete or [cancel] is called; [onText] receives the reply as it is written.
     *
     * Every reply gets its own conversation, closed afterwards. A cancelled conversation is
     * never reused, which is what used to leave the chat unable to send another message.
     * Creating a conversation without constrained decoding costs about 3 ms (Phase 0).
     */
    fun reply(
        systemInstruction: String,
        history: List<Turn>,
        text: String,
        /**
         * The most tokens the reply may have. Without a limit a reply that does not stop runs to
         * the end of the context: one answer of about 1750 tokens took 14 minutes on a busy phone
         * (2026-11-13). Every caller states what its task can need.
         */
        maxTokens: Int? = null,
        onText: (String) -> Unit,
    ): String {
        val engine = checkNotNull(engine) { "No model is loaded" }
        val config = ConversationConfig(
            systemInstruction = Contents.of(systemInstruction),
            initialMessages = history.map { if (it.fromUser) Message.user(it.text) else Message.model(it.text) },
            maxOutputToken = maxTokens,
        )
        val conversation = engine.createConversation(config)
        cancelled = false
        looped = false
        active = conversation
        val done = CountDownLatch(1)
        val reply = StringBuilder()
        var failure: Throwable? = null
        try {
            conversation.sendMessageAsync(
                text,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (cancelled || looped) return
                        val piece = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        if (piece.isNotEmpty()) {
                            val cut = synchronized(reply) {
                                reply.append(piece)
                                LoopGuard.cut(reply.toString()).also { if (it >= 0) reply.setLength(it) }
                            }
                            if (cut >= 0) {
                                // The model is repeating itself: keep what came before and stop it.
                                looped = true
                                runCatching { conversation.cancelProcess() }
                            } else {
                                onText(piece)
                            }
                        }
                    }

                    override fun onDone() = done.countDown()

                    override fun onError(throwable: Throwable) {
                        failure = throwable
                        done.countDown()
                    }
                },
            )
            // The native side may not call back after a cancel, so the wait never depends on it.
            while (!done.await(POLL_MS, TimeUnit.MILLISECONDS)) {
                if (cancelled || looped) {
                    done.await(CANCEL_GRACE_MS, TimeUnit.MILLISECONDS)
                    break
                }
            }
        } finally {
            active = null
            lastStats = if (cancelled) null else readStats(conversation)
            lastStats?.let { Log.i(TAG, "reply $it") }
            runCatching { conversation.close() }
        }
        lastLooped = looped
        if (!cancelled && !looped) failure?.let { throw it }
        return synchronized(reply) { reply.toString() }
    }

    private fun readStats(conversation: Conversation): Stats? = runCatching {
        val tokens = runCatching { conversation.getTokenCount() }.getOrDefault(0)
        val info = if (benchmark) runCatching { conversation.getBenchmarkInfo() }.getOrNull() else null
        Stats(
            contextTokens = tokens,
            promptTokens = info?.lastPrefillTokenCount ?: 0,
            replyTokens = info?.lastDecodeTokenCount ?: 0,
            promptTokensPerSecond = info?.lastPrefillTokensPerSecond ?: 0.0,
            replyTokensPerSecond = info?.lastDecodeTokensPerSecond ?: 0.0,
            firstTokenSeconds = info?.timeToFirstTokenInSecond ?: 0.0,
        )
    }.getOrNull()

    /** Stops the reply being written, if any. Safe to call from another thread. */
    fun cancel() {
        cancelled = true
        runCatching { active?.cancelProcess() }
    }

    override fun close() {
        cancel()
        engine?.close()
        engine = null
        loaded = null
        backend = null
    }

    private companion object {
        const val TAG = "SEBRAIN_ML"
        const val POLL_MS = 200L
        const val CANCEL_GRACE_MS = 2000L
    }
}
