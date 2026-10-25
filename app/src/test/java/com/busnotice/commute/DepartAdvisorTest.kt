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
}
