package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 坐标是虚构的一条东西向直线，每站约 1.1 公里 */
class JoinPickTest {
    private val line = (0..5).map { 30.0 to 100.0 + it * 0.0115 }

    @Test fun middleStation() = assertEquals(3, JoinPick.pick(line, 30.0005, 100.0345, 300.0))
    @Test fun atOrigin() = assertNull(JoinPick.pick(line, 30.0, 100.0002, 300.0))
    @Test fun atLastStation() = assertNull(JoinPick.pick(line, 30.0, 100.0575, 300.0))
    @Test fun betweenStations() = assertNull(JoinPick.pick(line, 30.0, 100.0287, 300.0))
    @Test fun tooShort() = assertNull(JoinPick.pick(line.take(2), 30.0, 100.0115, 300.0))
}
