package org.mozilla.fenix.perf

/** Units are explicit: decimal megabits/second, not megabytes/second. */
internal object PerformanceMath {
    fun megabitsPerSecond(bytes: Long, elapsedNanos: Long): Double {
        require(bytes > 0 && elapsedNanos > 0)
        return bytes * 8.0 * 1_000.0 / elapsedNanos
    }

    fun percentile95(values: List<Long>): Long {
        require(values.isNotEmpty())
        return values.sorted()[((values.size * 95 + 99) / 100) - 1]
    }
}
