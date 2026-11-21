package com.sebrain.app.profile

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.sebrain.core.ml.KnownModel
import com.sebrain.core.ml.ModelFiles

/** What the phone has, as far as choosing models goes. */
data class DeviceSpec(val model: String, val soc: String, val totalRamBytes: Long, val freeBytes: Long) {
    companion object {
        fun current(context: Context): DeviceSpec {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            return DeviceSpec(Build.MODEL, Build.SOC_MODEL, memory.totalMem, context.filesDir.usableSpace)
        }
    }
}

enum class Role { SEARCH, CHAT }

enum class Hardware { NPU, GPU, CPU }

data class ModelAdvice(
    val model: KnownModel,
    val role: Role,
    val hardware: Hardware,
    val recommended: Boolean,
    val reason: String,
)

/**
 * Which known models suit a phone. The uncensored chat models are community builds that run on
 * the GPU; on a Tensor G5, Google's official E2B build is also offered for the NPU (see ChatEngine).
 */
object ModelAdvisor {
    /** A 12 GB phone reports about 10.9 GiB to Android, so the line sits below that. */
    const val LARGE_CHAT_MIN_RAM_BYTES = 10L shl 30
    private const val TENSOR_G5 = "Tensor G5"

    fun hasTensorNpu(spec: DeviceSpec): Boolean = spec.soc.equals(TENSOR_G5, ignoreCase = true)

    fun advise(spec: DeviceSpec): List<ModelAdvice> {
        val search = if (hasTensorNpu(spec)) {
            ModelAdvice(ModelFiles.EMBEDDING_NPU, Role.SEARCH, Hardware.NPU, true, "Compiled for the Tensor G5 NPU in this phone.")
        } else {
            ModelAdvice(ModelFiles.EMBEDDING_CPU, Role.SEARCH, Hardware.CPU, true, "Runs on the CPU of any phone.")
        }
        // On a Tensor G5 the NPU build is the default: FollowUpEvalTest (2026-11-19) gave it 27/27
        // like E4B, at 2.5 to 3 times the speed. The uncensored models stay one tap away.
        val npu = hasTensorNpu(spec)
        val large = spec.totalRamBytes >= LARGE_CHAT_MIN_RAM_BYTES
        val npuChat = ModelAdvice(
            ModelFiles.LLM_E2B_TENSOR_G5, Role.CHAT, Hardware.NPU, true,
            "Fast chat on the TPU: about three times faster, and it followed instructions as well as E4B in our tests. " +
                "Google's official model, so it may refuse some requests.",
        )
        val e4b = ModelAdvice(
            ModelFiles.LLM_E4B_ABLITERATED, Role.CHAT, Hardware.GPU, !npu && large,
            when {
                npu -> "Uncensored and larger, on the GPU: about three times slower. For requests the fast model refuses."
                large -> "Best answers. This phone has the memory for it."
                else -> "Needs a phone with 12 GB of memory."
            },
        )
        val e2b = ModelAdvice(
            ModelFiles.LLM_E2B_UNCENSORED, Role.CHAT, Hardware.GPU, !npu && !large,
            if (large) "Uncensored, smaller and faster than E4B, with simpler answers." else "Fits this phone's memory.",
        )
        return listOfNotNull(search, npuChat.takeIf { npu }, e4b, e2b)
    }

    /** [installed] chat models with the ones recommended for [spec] first, in advice order otherwise. */
    fun <T : KnownModel> preferred(spec: DeviceSpec, installed: List<T>): List<T> {
        val order = advise(spec).filter { it.role == Role.CHAT }.sortedByDescending { it.recommended }.map { it.model.fileName }
        return installed.sortedBy { model -> order.indexOf(model.fileName).let { if (it < 0) order.size else it } }
    }
}
