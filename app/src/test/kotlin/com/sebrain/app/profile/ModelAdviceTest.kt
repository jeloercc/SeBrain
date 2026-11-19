package com.sebrain.app.profile

import com.sebrain.core.ml.ModelFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAdviceTest {
    private val gib = 1L shl 30

    @Test
    fun `a Pixel 10 gets the NPU search model and the larger chat model`() {
        // The Pixel 10 (12 GB) reports about 10.9 GiB to Android.
        val advice = ModelAdvisor.advise(DeviceSpec("Pixel 10", "Tensor G5", 11_465_608L * 1024, 40 * gib))

        val search = advice.single { it.role == Role.SEARCH }
        assertEquals(ModelFiles.EMBEDDING_NPU, search.model)
        assertEquals(Hardware.NPU, search.hardware)
        assertTrue(advice.single { it.model == ModelFiles.LLM_E4B_ABLITERATED }.recommended)
        assertFalse(advice.single { it.model == ModelFiles.LLM_E2B_UNCENSORED }.recommended)
    }

    @Test
    fun `another chip gets the CPU search model`() {
        val advice = ModelAdvisor.advise(DeviceSpec("Phone", "Snapdragon 8", 16 * gib, 40 * gib))

        val search = advice.single { it.role == Role.SEARCH }
        assertEquals(ModelFiles.EMBEDDING_CPU, search.model)
        assertEquals(Hardware.CPU, search.hardware)
    }

    @Test
    fun `a phone with little memory gets the smaller chat model`() {
        val advice = ModelAdvisor.advise(DeviceSpec("Phone", "Tensor G5", 7 * gib, 40 * gib))

        assertFalse(advice.single { it.model == ModelFiles.LLM_E4B_ABLITERATED }.recommended)
        assertTrue(advice.single { it.model == ModelFiles.LLM_E2B_UNCENSORED }.recommended)
    }

    @Test
    fun `exactly one chat model is recommended`() {
        listOf(4L, 10L, 24L).forEach { ram ->
            val advice = ModelAdvisor.advise(DeviceSpec("Phone", "Tensor G5", ram * gib, 40 * gib))
            assertEquals(1, advice.count { it.role == Role.CHAT && it.recommended })
        }
    }
}
