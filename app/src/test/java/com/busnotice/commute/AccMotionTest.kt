package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 合成加速度窗口（每 2 秒一个）测上车判断：最后几步 + 列车开动 */
class AccMotionTest {
    private val t0 = 1_000_000_000L

    /** 按段拼窗口：每段 n 个窗口，m/sd 由下标决定；返回窗口和段边界 */
    private class Seq(start: Long) {
        val ws = mutableListOf<AccMotion.Win>()
        var t = start; private set
        fun add(n: Int, f: (Int) -> Pair<Double, Double>): Long {
            val begin = t
            repeat(n) { i -> t += AccMotion.WIN_MS; val (m, sd) = f(i); ws += AccMotion.Win(t, m, sd) }
            return begin
        }
    }

    private val walk = 1.8 to 0.5
    private val shake = 0.4 to 0.2
    private val small = 0.3 to 0.15
    private val still = 0.05 to 0.02

    /** 进站走 20s → 扶梯 60s → 站台静止/小晃交替 120s（各 10 秒一段）→ 跨进车门几步 → 列车连续晃 150s */
    private fun station(): Triple<Seq, Long, Pair<Long, Long>> {
        val s = Seq(t0)
        s.add(10) { walk }                                              // 走路 20s
        val escalatorStart = s.add(30) { shake }                        // 扶梯 60s
        s.add(60) { i -> if ((i / 5) % 2 == 0) still else small }       // 等车 120s
        s.add(3) { i -> if (i == 1) still else walk }                   // 3 个窗口 2 个走路
        val stepsEnd = s.t
        val departStart = s.add(75) { shake }                           // 列车 150s
        return Triple(s, escalatorStart, stepsEnd to departStart)
    }

    @Test fun lastWalkEndIsBoardingSteps() {
        val (s, _, p) = station()
        assertEquals(p.first, AccMotion.lastWalkEnd(s.ws, t0 - 1, s.t))
    }

    @Test fun departureFromLastSteps() {
        val (s, _, p) = station()
        val from = AccMotion.lastWalkEnd(s.ws, t0 - 1, s.t)!!
        assertEquals(p.second, AccMotion.departureSince(s.ws, from, s.t))
    }

    @Test fun escalatorIsNotDeparture() {
        val (s, escalatorStart, p) = station()
        val got = AccMotion.departureSince(s.ws, escalatorStart - 1, s.t, spanMs = 90_000L)
        assertNotEquals(escalatorStart, got)
        assertEquals(p.second, got)
    }

    @Test fun walkInsideShakeBreaksSegment() {
        val s = Seq(t0)
        s.add(30) { still }                                             // 静止 60s
        s.add(70) { i -> if (i == 35) walk else shake }                // 晃 140s，中间夹 1 个走路窗口
        // 走路窗口前后各不到 90s：整段都不算
        assertNull(AccMotion.departureSince(s.ws, t0, s.t))
        // 单个走路窗口不算「走了几步」
        assertNull(AccMotion.lastWalkEnd(s.ws, t0, s.t))
        // 之后再晃 30s：只能从走路窗口之后算起，不会回到晃动开始
        s.add(15) { shake }
        val walkWinEnd = t0 + 30 * AccMotion.WIN_MS + 36 * AccMotion.WIN_MS
        assertEquals(walkWinEnd, AccMotion.departureSince(s.ws, t0, s.t))
    }
}
