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
}
