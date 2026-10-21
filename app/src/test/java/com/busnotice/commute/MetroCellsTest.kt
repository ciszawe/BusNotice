package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetroCellsTest {
    private val UNDER = 1001   // 地下专网区号（占位）
    private val UP = 2002      // 地面区号（占位）

    private fun snap(t: Long, tac: Int?, lte: Int? = null) = CellSnap(t, tac, null, null, lte)

    private fun ride(l: MetroTacLearner, tac: Int, n: Int = 40) {
        repeat(n) { l.sampleRide(snap(it * 5_000L, tac)) }
        l.endRide()
    }

    @Test fun learnsAfterTwoRides() {
        val l = MetroTacLearner()
        ride(l, UNDER); assertFalse(l.isMetroTac(UNDER))
        ride(l, UNDER); assertTrue(l.isMetroTac(UNDER))
    }

    @Test fun shortRideNotCounted() {
        val l = MetroTacLearner()
        ride(l, UNDER, n = 10); ride(l, UNDER, n = 10)
        assertFalse(l.isMetroTac(UNDER))
    }

    @Test fun mixedRideNotCounted() {
        val l = MetroTacLearner()
        repeat(20) { l.sampleRide(snap(it * 5_000L, UNDER)) }
        repeat(20) { l.sampleRide(snap(it * 5_000L, UP)) }
        assertNull(l.endRide())
    }

    @Test fun seenOnSurfaceIsVetoed() {
        val l = MetroTacLearner()
        ride(l, UNDER); ride(l, UNDER)
        repeat(2) { l.sampleSurface(snap(0, UNDER)); l.endSurface() }
        assertFalse(l.isMetroTac(UNDER))
    }

    @Test fun jsonRoundTrip() {
        val l = MetroTacLearner(); ride(l, UNDER); ride(l, UNDER)
        assertTrue(MetroTacLearner.fromJson(l.toJson()).isMetroTac(UNDER))
    }

    @Test fun gateEnterAndExit() {
        val g = MetroGate { it == UNDER }
        assertNull(g.feed(snap(0, UP)))
        assertNull(g.feed(snap(5_000, UNDER)))
        assertEquals(MetroGate.Edge.ENTER, g.feed(snap(10_000, UNDER))?.first)
        assertNull(g.feed(snap(15_000, null)))       // 掉到 4G 不算离开
        assertNull(g.feed(snap(20_000, UP)))
        assertNull(g.feed(snap(25_000, UP)))
        val exit = g.feed(snap(30_000, UP))
        assertEquals(MetroGate.Edge.EXIT, exit?.first)
        assertEquals(20_000L, exit?.second)          // 出站时刻 = 第一次离开
    }

    @Test fun gateBlipIgnored() {
        val g = MetroGate { it == UNDER }
        g.feed(snap(0, UNDER)); g.feed(snap(5_000, UNDER))
        assertNull(g.feed(snap(10_000, UP)))
        assertNull(g.feed(snap(15_000, UNDER)))      // 抖一下又回来
        assertNull(g.feed(snap(20_000, UP)))
        assertNull(g.feed(snap(25_000, UP)))
    }

    @Test fun enbFromCellId() = assertEquals(12345, CellSnap(0, null, null, null, 12345 * 256 + 150).enb)

    @Test fun fpCollapsesSectors() {
        val r = MetroFpRecorder()
        listOf(10 * 256 + 1, 10 * 256 + 2, 11 * 256 + 1, 12 * 256 + 1).forEachIndexed { i, id -> r.sample(CellSnap(i * 5_000L, null, null, null, id)) }
        assertEquals(3, r.finish()?.length())
    }
}
