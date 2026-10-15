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

    @Test fun metroNextFromTip() {
        assertEquals(4, Decision.metroNextMin("上一班10分钟前过站", 7))
        assertEquals(null, Decision.metroNextMin("暂无数据", 7))
        val m = Candidate(Candidate.Type.METRO, "4号线", "D站", 0.0, 0.0, headwayMin = 7)
        val a = Decision.decide(listOf(Decision.Option(m, 0, emptyList(), "上一班10分钟前过站")))
        assertEquals("约4分", a.headline)
    }

    @Test fun metroNameDetected() {
        assertTrue(Candidate.looksLikeMetro("4号线"))
        assertTrue(!Candidate.looksLikeMetro("604"))
        assertEquals("4号线", Candidate(Candidate.Type.BUS, "4号线", "D站", 0.0, 0.0).label())
        assertEquals("15路", bus().label())
    }

    @Test fun motion() {
        assertEquals(Motion.State.RIDE, Motion.classify(listOf(8f, 9f, 10f), onRoute = true))
        assertEquals(Motion.State.UNKNOWN, Motion.classify(listOf(8f, 9f, 10f), onRoute = false))
        assertEquals(Motion.State.WALK, Motion.classify(listOf(1.2f, 1.4f, 1.3f), onRoute = true))
        assertEquals(Motion.State.STILL, Motion.classify(listOf(0f, 0.1f, 0.2f), onRoute = false))
    }

    @Test fun learnWindow() {
        assertEquals(null, Learn.suggest(listOf(1100, 1110)))
        val s = Learn.suggest(listOf(1080, 1095, 1110, 1140, 1170, 1200, 1230))!!
        assertTrue(s.start <= 1080 - 5 && s.end >= 1200 && s.start % 5 == 0 && s.end % 5 == 0)
    }

    @Test fun metroLiveTrains() {
        val m = Candidate(Candidate.Type.METRO, "4号线", "D站", 0.0, 0.0, headwayMin = 7)
        val a = Decision.decide(listOf(Decision.Option(m, 0,
            listOf(Decision.Arrival(250, 2, "t1")), "上一班20分钟前过站")))
        assertEquals("5分", a.headline)
        assertTrue(a.detail.contains("5 分钟后进站"))
        assertEquals(null, Decision.metroNextMin("上一班20分钟前过站", 7))
    }

    @Test fun metroScheduleFromFirstTrain() {
        // 首班 06:30：平峰 10 分钟排到 07:00，之后高峰 5 分钟 → 07:00 07:05
        assertEquals(listOf(300, 600), Decision.metroSchedule(6 * 3600 + 55 * 60, 390, 1410, true, 5, 10))
        assertEquals(listOf(600), Decision.metroSchedule(6 * 3600 + 20 * 60, 390, 1410, true, 5, 10, n = 1))
        assertTrue(Decision.metroSchedule(23 * 3600 + 40 * 60, 390, 1410, true, 5, 10).isEmpty())
    }

    @Test fun mixedLegNeverSaysNoBusWhenMetroKnown() {
        val m = Candidate(Candidate.Type.METRO, "4号线", "D站", 0.0, 0.0, headwayMin = 5)
        val a = Decision.decide(listOf(
            Decision.Option(bus(), 0, emptyList(), "暂无车辆"),
            Decision.Option(m, 0, listOf(Decision.Arrival(170, estimated = true))),
        ))
        assertEquals(Decision.Mode.METRO, a.mode)
        assertEquals("约3分", a.headline)
    }
}
