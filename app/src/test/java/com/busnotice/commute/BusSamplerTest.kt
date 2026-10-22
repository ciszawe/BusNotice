package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BusSamplerTest {
    private val bus = Candidate(Candidate.Type.BUS, "示例线", "示例站", 0.0, 0.0, lineId = "L1", stationId = "S1", targetOrder = 5)
    // 工作日 18:00–19:30 的行程，采样窗口到 22:00
    private val trip = Trip(name = "示例", legs = listOf(Leg(listOf(bus))), startMinute = 18 * 60, endMinute = 19 * 60 + 30)

    @Test fun vanishedSoonBusArrives() {
        val t0 = 1_000_000L
        val got = BusSampler.arrivals(t0, mapOf("a" to 90, "b" to 900), t0 + 240_000L, setOf("b"))
        assertEquals(listOf("a" to t0 + 90_000L), got)
    }

    @Test fun farBusVanishingIsIgnored() {
        val t0 = 1_000_000L
        // 预估 20 分钟才到却不见了：多半是数据跳了，不算到站
        assertTrue(BusSampler.arrivals(t0, mapOf("a" to 1200), t0 + 240_000L, emptySet()).isEmpty())
    }

    @Test fun longGapIsIgnored() {
        val t0 = 1_000_000L
        assertTrue(BusSampler.arrivals(t0, mapOf("a" to 60), t0 + 15 * 60_000L, emptySet()).isEmpty())
    }

    @Test fun samplesEvery4MinInsideWindow() {
        val now = LocalDateTime.of(2026, 10, 22, 20, 30)   // 周四，窗口结束后的尾巴里
        assertEquals(now.plusMinutes(4), BusSampler.nextAt(listOf(trip), now))
    }

    @Test fun waitsForNextWindow() {
        val thuNight = LocalDateTime.of(2026, 10, 22, 22, 5)
        assertEquals(LocalDateTime.of(2026, 10, 23, 18, 0), BusSampler.nextAt(listOf(trip), thuNight))
        val friNight = LocalDateTime.of(2026, 10, 23, 23, 0)
        assertEquals(LocalDateTime.of(2026, 10, 26, 18, 0), BusSampler.nextAt(listOf(trip), friNight))
    }

    @Test fun keyHidesIds() {
        val k = BusSampler.key(bus)
        assertEquals(8, k.length)
        assertTrue(!k.contains("L1") && !k.contains("S1"))
    }
}
