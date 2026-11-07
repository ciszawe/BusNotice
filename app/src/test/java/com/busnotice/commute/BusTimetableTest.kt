package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BusTimetableTest {
    private fun m(h: Int, mi: Int) = h * 60 + mi
    // 两周工作日，晚上 21:28–21:30 一班，20:46–20:50 一班
    private val days: Map<LocalDate, List<Int>> = (0 until 14).map { LocalDate.of(2026, 10, 22).plusDays(it.toLong()) }
        .filter { it.dayOfWeek.value <= 5 }
        .mapIndexed { i, d -> d to listOf(m(20, 46 + i % 5), m(21, 28 + i % 3)) }.toMap()

    @Test fun predictsNextClusterAfterNow() {
        val p = BusTimetable.predictFrom(days, LocalDate.of(2026, 11, 6), m(21, 5))!!
        assertEquals(m(21, 29), p.mid)
        assertEquals(m(21, 28), p.lo)
        assertEquals(m(21, 30), p.hi)
    }

    @Test fun weekendUsesOnlyWeekendDays() {
        assertNull(BusTimetable.predictFrom(days, LocalDate.of(2026, 11, 7), m(21, 5)))
    }

    @Test fun irregularGivesNothing() {
        val messy = days.keys.mapIndexed { i, d -> d to listOf(m(21, 0) + (i * 7) % 40) }.toMap()
        assertNull(BusTimetable.predictFrom(messy, LocalDate.of(2026, 11, 6), m(20, 55)))
    }
}
