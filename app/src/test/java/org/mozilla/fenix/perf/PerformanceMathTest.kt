package org.mozilla.fenix.perf

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceMathTest {
    @Test
    fun `throughput uses decimal megabits and nanoseconds`() {
        assertEquals(8.0, PerformanceMath.megabitsPerSecond(1_000_000, 1_000_000_000), 0.0001)
        assertEquals(41.94304, PerformanceMath.megabitsPerSecond(10_485_760, 2_000_000_000), 0.0001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero duration is unavailable rather than infinite throughput`() {
        PerformanceMath.megabitsPerSecond(1, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty transfer is not a speed result`() {
        PerformanceMath.megabitsPerSecond(0, 100)
    }

    @Test
    fun `frame percentile uses nearest rank with unsorted samples`() {
        assertEquals(19L, PerformanceMath.percentile95((20L downTo 1L).toList()))
        assertEquals(7L, PerformanceMath.percentile95(listOf(7L)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing frame samples cannot produce a zero jank result`() {
        PerformanceMath.percentile95(emptyList())
    }
}
