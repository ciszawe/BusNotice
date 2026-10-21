package com.busnotice.commute

import com.busnotice.commute.AccMotion.Win
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkLearnTest {
    // 原点用占位坐标（赤道附近），1e-4 度纬度约 11 米
    private fun fix(t: Long, dLat: Double, acc: Float = 10f) = WalkLearn.Fix(t, dLat, 0.0, acc)
    private fun walk(t: Long) = Win(t, 1.8, 0.5)
    private fun still(t: Long) = Win(t, 0.05, 0.02)

    @Test fun pathIgnoresJitterAndBadFixes() {
        val f = listOf(fix(0, 0.0), fix(1, 0.00002), fix(2, 0.001), fix(3, 0.002, acc = 200f), fix(4, 0.002))
        // 0→0.00002 约 2 米（抖动不算），→0.001 约 109 米，0.002 那个坏点跳过，→0.002 约 111 米
        val p = WalkLearn.pathM(f)
        assertTrue(p in 210.0..235.0)
    }

    @Test fun walkStartSkipsStillAndBlips() {
        // 30 秒处走了一下就停（站起来倒杯水），140 秒起才真正出门
        val w = (0 until 30).map { still(it * 2_000L) } + listOf(walk(60_000)) + (31 until 70).map { still(it * 2_000L) } +
            (70 until 130).map { walk(it * 2_000L) }
        assertEquals(140_000L, WalkLearn.walkStart(w, 0, 300_000))
    }

    @Test fun arriveAtFindsContinuousTail() {
        val f = listOf(fix(0, 0.0), fix(60_000, 0.003), fix(120_000, 0.0050), fix(180_000, 0.00502), fix(240_000, 0.00499))
        assertEquals(120_000L, WalkLearn.arriveAt(f, 0.005, 0.0))
    }

    @Test fun buildComputesSpeed() {
        val fixes = (0..10).map { fix(it * 30_000L, it * 0.0003) }   // 每 30 秒约 33 米 ≈ 1.1 米/秒
        val wins = (0 until 150).map { walk(it * 2_000L) }
        val s = WalkLearn.build(1, "last", 0, 300_000, fixes, wins, false)
        assertNotNull(s); assertTrue(s!!.speed!! in 1.0..1.25); assertEquals(300, s.durSec)
    }

    @Test fun buildRejectsTooShort() = assertNull(WalkLearn.build(1, "last", 0, 10_000, emptyList(), emptyList(), false))

    @Test fun noSpeedWithoutWalking() {
        val fixes = (0..10).map { fix(it * 30_000L, it * 0.0003) }
        assertNull(WalkLearn.build(1, "first", 0, 300_000, fixes, emptyList(), true)?.speed)
    }
}
