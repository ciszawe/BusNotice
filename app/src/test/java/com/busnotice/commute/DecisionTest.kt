package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionTest {
    private fun bus(name: String = "15") = Candidate(Candidate.Type.BUS, name, "A站", 0.0, 0.0)
    private fun opt(walk: Int, vararg eta: Int, c: Candidate = bus()) =
        Decision.Option(c, walk, eta.map { Decision.Arrival(it, 3, "b$it") })

    @Test fun leaveLaterWhenBusFar() {
        val a = Decision.decide(listOf(opt(433, 1200)))
        assertEquals(Decision.Mode.LEAVE_LATER, a.mode)
        assertEquals(1200 - 433 - 60, a.leaveInSec)
    }

    @Test fun leaveNowWhenTight() {
        assertEquals(Decision.Mode.LEAVE_NOW, Decision.decide(listOf(opt(433, 520))).mode)
    }

    @Test fun skipUncatchableBus() {
        assertEquals(900, Decision.decide(listOf(opt(433, 200, 900))).arrival!!.etaSec)
    }

    @Test fun hurryWhenNothingCatchable() {
        assertEquals(Decision.Mode.HURRY, Decision.decide(listOf(opt(433, 300))).mode)
    }

    @Test fun atStop() {
        assertEquals(Decision.Mode.AT_STOP, Decision.decide(listOf(opt(0, 240))).mode)
    }

    @Test fun picksEarliestAcrossLinesAndShuttle() {
        val shuttle = Candidate(Candidate.Type.SHUTTLE, "通勤车", "B", 0.0, 0.0, departures = listOf(7 * 60 + 40))
        val now = 7 * 3600 + 30 * 60 // 07:30
        val s = Decision.Option(shuttle, 300, Decision.shuttleArrivals(shuttle, now))
        val a = Decision.decide(listOf(opt(300, 1500), s))
        assertEquals("通勤车", a.option!!.candidate.name)
        assertEquals(600, a.arrival!!.etaSec)
    }

    @Test fun connection() {
        val a = Decision.decide(listOf(opt(600, 500, 900)), connecting = true)
        assertEquals(Decision.Mode.CONNECT, a.mode)
        assertEquals(900, a.arrival!!.etaSec)
        val b = Decision.decide(listOf(opt(600, 500)), connecting = true)
        assertEquals(Decision.Mode.CONNECT_MISS, b.mode)
    }

    @Test fun metroOnly() {
        val m = Candidate(Candidate.Type.METRO, "2号线", "C站", 0.0, 0.0, headwayMin = 5)
        val a = Decision.decide(listOf(Decision.Option(m, 300, emptyList())))
        assertEquals(Decision.Mode.METRO, a.mode)
        assertTrue(a.detail.contains("5 分钟一班"))
    }

    @Test fun gcjRoundTrip() {
        val (lat, lng) = Geo.gcjToWgs(31.23, 121.47)
        assertTrue(Geo.distanceM(lat, lng, 31.23, 121.47) in 300.0..800.0)
    }

    @Test fun envelopeAndSign() {
        val raw = "**YGKJ{\"jsonr\":{\"data\":{\"x\":1},\"status\":\"00\"}}YGKJ##"
        assertEquals(1, ChelaileApi.parseEnvelope(raw).getInt("x"))
        assertEquals(32, ChelaileApi.sign(linkedMapOf("a" to "1")).length)
    }
}
