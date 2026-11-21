package com.sebrain.cognition

/**
 * When a `CognitionWorker` run must stop, by `docs/design-cognition-worker.md` §1: at most
 * [maxSegments] segments, and a segment starts only if it is expected to end inside the
 * [budgetMs]. The expectation is the p95 of the latest segments (35 s until there are enough);
 * counting segments alone is not enough, because in the background a segment measured 29 s
 * against 12 s with the screen on.
 *
 * Immutable: [record] returns a new budget.
 */
class CognitionBudget private constructor(
    private val budgetMs: Long,
    private val maxSegments: Int,
    private val recentMs: List<Long>,
    val segments: Int,
) {
    constructor(budgetMs: Long = BUDGET_MS, maxSegments: Int = MAX_SEGMENTS) : this(budgetMs, maxSegments, emptyList(), 0)

    /** Why a run stops before its next segment, or null if it may go on. */
    enum class Stop { SEGMENT_LIMIT, TIME_BUDGET }

    /** The p95 of the latest segments, or the initial estimate while there are fewer than [MIN_SAMPLES]. */
    val expectedMs: Long
        get() {
            if (recentMs.size < MIN_SAMPLES) return INITIAL_P95_MS
            val sorted = recentMs.sorted()
            return sorted[((sorted.size - 1) * P95).toInt()]
        }

    /** Whether a segment may start after [elapsedMs] of this run. */
    fun stopBefore(elapsedMs: Long): Stop? = when {
        segments >= maxSegments -> Stop.SEGMENT_LIMIT
        elapsedMs + expectedMs >= budgetMs -> Stop.TIME_BUDGET
        else -> null
    }

    /** The budget after a segment that took [ms]. Only the latest [WINDOW] segments count. */
    fun record(ms: Long): CognitionBudget =
        CognitionBudget(budgetMs, maxSegments, (recentMs + ms).takeLast(WINDOW), segments + 1)

    companion object {
        /** 8 minutes: a regular Worker is stopped at about 10. */
        const val BUDGET_MS = 8 * 60 * 1000L
        const val MAX_SEGMENTS = 25
        const val INITIAL_P95_MS = 35_000L
        const val MIN_SAMPLES = 5
        const val WINDOW = 20
        private const val P95 = 0.95
    }
}
