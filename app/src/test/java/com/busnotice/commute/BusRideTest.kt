package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusRideTest {
    // 6 个站，每站间约 500 米（沿纬度方向）
    private val stops = (0..5).map { ChelaileApi.StopPoint("s$it", "站$it", it, 30.0 + it * 0.0045, 120.0) }

    @Test fun advanceMovesForwardByDistance() {
        val s0 = BusRide.snap(stops, 30.0, 120.0)!!
        assertEquals(0, s0.seg)
        val s1 = BusRide.advance(stops, s0, 1_100.0)
        assertEquals(2, s1.seg)
        assertTrue(s1.remainM < s0.remainM)
    }

    @Test fun advanceStopsBeforeLastStop() {
        val s0 = BusRide.snap(stops, 30.0, 120.0)!!
        val s1 = BusRide.advance(stops, s0, 50_000.0)
        assertEquals(3, s1.seg)          // 最多推到倒数第二站
        assertEquals(2, s1.stopsLeft)
    }

    @Test fun advanceZeroKeepsSnap() {
        val s0 = BusRide.snap(stops, 30.0, 120.0)!!
        assertEquals(s0, BusRide.advance(stops, s0, 0.0))
    }
}
