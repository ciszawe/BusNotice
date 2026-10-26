package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class DepartAdvisorTest {
    private val m = 60_000L

    @Test fun picksFirstReachableRealtimeBus() {
        val p = DepartAdvisor.plan(listOf(20 * m, 45 * m, 60 * m), 33, earliest = 0)!!
        assertEquals(45 * m, p.busAt); assertEquals(12 * m, p.leaveAt); assertFalse(p.projected)
    }

    @Test fun respectsEarliest() {
        val p = DepartAdvisor.plan(listOf(40 * m, 55 * m), 30, earliest = 15 * m)!!
        assertEquals(55 * m, p.busAt)
    }

    @Test fun projectsByHeadway() {
        val p = DepartAdvisor.plan(listOf(10 * m, 22 * m), 33, earliest = 0)!!
        assertTrue(p.projected); assertEquals(34 * m, p.busAt); assertEquals(1 * m, p.leaveAt)
    }

    @Test fun defaultHeadwayWithOneBus() {
        val p = DepartAdvisor.plan(listOf(10 * m), 33, earliest = 0)!!
        assertEquals(40 * m, p.busAt)
    }

    @Test fun noBuses() = assertNull(DepartAdvisor.plan(emptyList(), 30, 0))

    @Test fun beyondHorizon() = assertNull(DepartAdvisor.plan(listOf(5 * m), 30, earliest = 200 * m))

    @Test fun timetableClusters() {
        val t = DepartAdvisor.timetable(listOf(
            listOf(1125.0, 1142.0, 1159.0, 1179.0, 1210.0),
            listOf(1117.0, 1136.0, 1157.0, 1179.0, 1208.0),
            listOf(1120.0, 1139.0, 1157.0, 1179.0, 1209.0, 1300.0),
        ))
        assertEquals(5, t.size)
        assertEquals(1179.0, t[3], 0.01)
    }

    @Test fun scheduleBeatsGap() {
        // 实时只看到 1 分钟后的一班；往日时刻表里后面还有 19、41 分钟后的
        val p = DepartAdvisor.plan(listOf(1 * m), 36, earliest = 0, scheduled = listOf(1 * m, 19 * m, 41 * m))!!
        assertEquals(41 * m, p.busAt); assertEquals("sched", p.basis)
    }

    @Test fun scheduleWithoutRealtime() {
        val p = DepartAdvisor.plan(emptyList(), 36, earliest = 0, scheduled = listOf(20 * m, 42 * m))!!
        assertEquals(42 * m, p.busAt)
    }
}
