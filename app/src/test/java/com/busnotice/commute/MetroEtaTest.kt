package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetroEtaTest {
    private fun ride(vararg p: Pair<Long, Long>) = p.toList()

    @Test fun learnsRemainingToTerminal() {
        val rides = listOf(
            ride(1L to 0L, 2L to 240_000L, 3L to 360_000L, 9L to 500_000L),
            ride(1L to 0L, 2L to 230_000L, 3L to 350_000L, 9L to 470_000L),
            ride(2L to 0L, 3L to 125_000L, 9L to 255_000L),
        )
        val m = MetroEta.build(rides)
        assertEquals(0L, m[9L])
        assertEquals(130_000L, m[3L])
        assertEquals(255_000L, m[2L])
    }

    @Test fun needsThreeRides() {
        assertTrue(MetroEta.build(listOf(ride(1L to 0L, 2L to 1L, 3L to 2L))).isEmpty())
    }

    @Test fun unstableTerminalIgnored() {
        val rides = listOf(
            ride(1L to 0L, 2L to 1L, 3L to 2L), ride(1L to 0L, 2L to 1L, 4L to 2L), ride(1L to 0L, 2L to 1L, 5L to 2L),
        )
        assertTrue(MetroEta.build(rides).isEmpty())
    }

    private fun cell(t: Long, tac: Int, lte: Long) =
        """{"cells":[{"type":"nr","id":1,"tac":$tac},{"type":"lte","id":$lte,"tac":9}],"t":$t,"k":"cell"}"""

    @Test fun traceRideKeepsUndergroundOnly() {
        val ug = 7
        val lines = listOf(
            cell(0, 5, 100L shl 8),                 // 地面：不算
            cell(1_000, ug, 200L shl 8),
            cell(30_000, ug, (200L shl 8) or 3L),      // 同一基站换扇区：不重复
            cell(60_000, ug, 300L shl 8),
            cell(90_000, ug, 400L shl 8),
            """{"e":"a11y","k":"ev","t":1}""",
            cell(120_000, 5, 500L shl 8),            // 出站后地面
        )
        val r = MetroEta.traceRide(lines) { it == ug }!!
        assertEquals(listOf(200L to 1_000L, 300L to 60_000L, 400L to 90_000L), r)
    }
}
