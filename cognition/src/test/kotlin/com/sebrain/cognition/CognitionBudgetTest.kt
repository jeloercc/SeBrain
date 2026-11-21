package com.sebrain.cognition

import com.sebrain.cognition.CognitionBudget.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CognitionBudgetTest {
    @Test
    fun `a fresh run starts and expects the initial p95`() {
        val budget = CognitionBudget()

        assertNull(budget.stopBefore(0))
        assertEquals(CognitionBudget.INITIAL_P95_MS, budget.expectedMs)
    }

    @Test
    fun `no segment starts if the initial estimate would pass the budget`() {
        val budget = CognitionBudget()

        // 7.5 min in, 35 s more would pass 8 min.
        assertEquals(Stop.TIME_BUDGET, budget.stopBefore(450_000))
        assertNull(budget.stopBefore(440_000))
    }

    @Test
    fun `after enough segments the estimate is their p95`() {
        val budget = (1..10).fold(CognitionBudget()) { b, i -> b.record(i * 1_000L) }

        // p95 of 1..10 s, by index (10 - 1) * 0.95 = 8: the 9th smallest.
        assertEquals(9_000L, budget.expectedMs)
    }

    @Test
    fun `the segment limit stops the run whatever the time`() {
        val budget = (1..CognitionBudget.MAX_SEGMENTS).fold(CognitionBudget()) { b, _ -> b.record(1_000) }

        assertEquals(Stop.SEGMENT_LIMIT, budget.stopBefore(0))
    }

    @Test
    fun `only the latest segments count, so a slow start is forgotten`() {
        val slowStart = (1..CognitionBudget.WINDOW).fold(CognitionBudget(maxSegments = 100)) { b, _ -> b.record(60_000) }
        val recovered = (1..CognitionBudget.WINDOW).fold(slowStart) { b, _ -> b.record(10_000) }

        assertEquals(10_000L, recovered.expectedMs)
    }

    @Test
    fun `recording returns a new budget and leaves the old one`() {
        val before = CognitionBudget()
        val after = before.record(5_000)

        assertEquals(0, before.segments)
        assertEquals(1, after.segments)
    }
}
