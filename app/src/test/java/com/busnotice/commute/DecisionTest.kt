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

    @Test fun metroResidualWrapsToNearestTrain() {
        // 平峰 10 分钟，首班 06:00：10:02 上车 → 比 10:00 那班晚 120 秒；10:08 → 比 10:10 早 120 秒
        assertEquals(120, Decision.metroResidual(10 * 3600 + 2 * 60, 360, 1410, false, 5, 10))
        assertEquals(-120, Decision.metroResidual(10 * 3600 + 8 * 60, 360, 1410, false, 5, 10))
    }

    @Test fun planPicksLatestTrainForEarliestBus() {
        // 地铁每 5 分钟（0,300,600,...），坐 20 分钟，换乘走 3 分钟；走到地铁站 5 分钟
        val trains = (0..20).map { it * 300 }
        val buses = listOf(Triple("32路", 1800, false), Triple("32路", 2700, false))
        val p = Decision.planDeparture(300, trains, 1200, 180, buses)
        // 公交 1800 秒：最晚地铁 t+1200+180+60<=1800 → t=300；出发 300-300-60 <0 → 赶不上；下一班 2700 → t=1200，出发 840
        assertEquals(1, p.size)
        assertEquals(840, p[0].leaveSec)
        assertEquals(1200, p[0].trainSec)
        assertEquals(2700 - (1200 + 1200 + 180), p[0].waitSec)
    }

    @Test fun extendBusesByObservedGap() {
        val l = Decision.extendBuses("32路", listOf(300, 1200), 0, horizonSec = 3000)
        assertEquals(listOf(300, 1200, 2100, 3000), l.map { it.second })
        assertEquals(listOf(false, false, true, true), l.map { it.third })
    }

    @Test fun metroScheduleKeepsDwellingTrain() {
        // 首班 6:00，平峰 10 分钟；现在 6:10:20 → 6:10 那班还在站内（-20 秒）
        val l = Decision.metroSchedule(6 * 3600 + 620, 360, 1380, false, 10, 10, 3, Decision.DWELL_SEC)
        assertEquals(listOf(-20, 580, 1180), l)
    }

    @Test fun connectMissPicksCatchableNotGoneBus() {
        // 还要 17 分钟到换乘站：0 秒那班根本赶不上，应该提 17 分 20 秒那班（不留余量刚好来得及）
        val a = Decision.decide(listOf(opt(1020, 0, 1040)), connecting = true)
        assertEquals(Decision.Mode.CONNECT_MISS, a.mode)
        assertEquals(1040, a.arrival!!.etaSec)
        assertTrue(!a.detail.contains("0 分钟到"))
    }

    @Test fun connectMissNoCatchableBus() {
        val a = Decision.decide(listOf(opt(1020, 0, 300)), connecting = true)
        assertEquals(Decision.Mode.CONNECT_MISS, a.mode)
        assertEquals(null, a.arrival)
        assertTrue(a.detail.contains("暂无赶得上的车"))
        assertTrue(!a.detail.contains("0 分钟到"))
        // 知道间隔（10 分钟）：300 → 900 → 1500 秒，第一班赶得上的约 25 分钟
        val c = Candidate(Candidate.Type.BUS, "15", "A站", 0.0, 0.0, headwayMin = 10)
        val b = Decision.decide(listOf(opt(1020, 0, 300, c = c)), connecting = true)
        assertTrue(b.detail.contains("下一班约 25 分钟"))
    }

    @Test fun neverShowZeroMinutes() {
        assertEquals("即将到站", Decision.arriveText(20))
        assertEquals("已到站", Decision.arriveText(0))
        assertEquals("3 分钟到", Decision.arriveText(180))
        assertEquals("即将到", Decision.minShort(10))
        val a = Decision.decide(listOf(opt(0, 10, 400)))
        assertEquals(Decision.Mode.AT_STOP, a.mode)
        assertTrue(a.detail.contains("即将到站") && !a.detail.contains("0 分钟"))
    }

    // ---------- 加速度活动分类 ----------
    private fun wins(vararg seg: Triple<Int, Double, Double>): List<AccMotion.Win> {
        // 每段 (窗口数, m, sd)，每 2 秒一个窗口，从 t=2000 开始
        val out = ArrayList<AccMotion.Win>()
        var t = 0L
        for ((k, m, sd) in seg) repeat(k) { t += 2_000; out += AccMotion.Win(t, m, sd) }
        return out
    }

    @Test fun accClassify() {
        assertEquals(AccMotion.Kind.STILL, AccMotion.classify(AccMotion.Win(0, 0.08, 0.03)))
        assertEquals(AccMotion.Kind.VEHICLE, AccMotion.classify(AccMotion.Win(0, 0.5, 0.2)))
        assertEquals(AccMotion.Kind.WALK, AccMotion.classify(AccMotion.Win(0, 1.9, 0.6)))
        assertEquals(AccMotion.Kind.WALK, AccMotion.classify(AccMotion.Win(0, 6.0, 2.5)))
        assertEquals(AccMotion.Kind.OTHER, AccMotion.classify(AccMotion.Win(0, 1.1, 0.3)))
    }

    @Test fun accTrainStopThenWalk() {
        // 坐车 60 秒 → 停稳 12 秒 → 走路 26 秒（中间夹一个慢窗口）
        val w = wins(Triple(30, 0.4, 0.15), Triple(6, 0.08, 0.03), Triple(6, 1.8, 0.5),
            Triple(1, 0.6, 0.3), Triple(6, 2.1, 0.7))
        val now = w.last().t
        val start = AccMotion.walkingSustainedSince(w, now)
        assertEquals(72_000L, start)   // 第 37 个窗口（t=74000）的开头
        assertTrue(AccMotion.hadStillBefore(w, start!!))
    }

    @Test fun accNoWalkWhileRiding() {
        val w = wins(Triple(40, 0.5, 0.2))
        assertEquals(null, AccMotion.walkingSustainedSince(w, w.last().t))
        // 走路太短（10 秒）也不算
        val s = wins(Triple(10, 0.4, 0.1), Triple(5, 2.0, 0.6))
        assertEquals(null, AccMotion.walkingSustainedSince(s, s.last().t))
        // 走路前没有停稳（一直在晃）：没有静止段
        val r = wins(Triple(30, 0.5, 0.2), Triple(12, 2.0, 0.6))
        val st = AccMotion.walkingSustainedSince(r, r.last().t)!!
        assertTrue(!AccMotion.hadStillBefore(r, st))
        // 两个非走路窗口连着：只算后面那段
        val g = wins(Triple(8, 2.0, 0.6), Triple(2, 0.5, 0.2), Triple(6, 2.0, 0.6))
        assertEquals(null, AccMotion.walkingSustainedSince(g, g.last().t))
    }

    // ---------- 公交沿线进度 ----------
    private fun stop(i: Int, name: String, lat: Double) = ChelaileApi.StopPoint("s$i", name, i, lat, 0.0)

    @Test fun busSliceAndSnap() {
        // 一条正北走向的线，每站约 1.1 公里
        val all = (1..8).map { stop(it, "S$it", it * 0.01) }
        val r = BusRide.slice(all, 2, "", Double.NaN, Double.NaN, "S6站", Double.NaN, Double.NaN)!!
        assertEquals(listOf("S2", "S3", "S4", "S5", "S6"), r.map { it.name })
        // 在 S3 和 S4 中间
        val sn = BusRide.snap(r, 0.035, 0.0005)!!
        assertEquals(1, sn.seg)
        assertEquals(3, sn.stopsLeft)
        assertTrue(sn.remainM in 2_600.0..2_900.0)
        // 防抖：到过第 3 段后，不会再投回第 0 段
        val back = BusRide.snap(r, 0.021, 0.0, minSeg = 2)!!
        assertEquals(2, back.seg)
        // 按坐标找上下车站
        val r2 = BusRide.slice(all, -1, "", 0.0301, 0.0, "", 0.0499, 0.0)!!
        assertEquals(listOf("S3", "S4", "S5"), r2.map { it.name })
    }
}
