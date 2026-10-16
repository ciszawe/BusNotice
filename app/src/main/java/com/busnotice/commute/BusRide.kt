package com.busnotice.commute

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 坐公交时沿线路算进度：纯计算，不依赖 Android，可单测。
 * 把当前位置投到上车站..下车站之间相邻两站的线段上，得出下一站、还剩几站、沿线还剩多少米。
 */
object BusRide {
    const val DEFAULT_M_PER_MIN = 250.0   // 约 15 km/h（含停站、红绿灯）

    /** 投影结果：seg = 在第 seg 站和 seg+1 站之间，frac = 段内位置 0..1，offM = 离线路多远 */
    data class Snap(val seg: Int, val frac: Double, val offM: Double, val remainM: Double, val stopsLeft: Int)

    private fun usable(p: ChelaileApi.StopPoint) = !p.lat.isNaN() && !p.lng.isNaN() && !(p.lat == 0.0 && p.lng == 0.0)

    private fun same(a: String, b: String) = a.isNotEmpty() && b.isNotEmpty() && a.removeSuffix("站") == b.removeSuffix("站")

    /**
     * 从线路全部站里截出上车站..下车站（含两端）。上车站先按站序、再按站名、最后按坐标最近找；
     * 下车站在上车站之后按站名、再按坐标最近找。找不到或顺序不对返回 null。
     */
    fun slice(
        all: List<ChelaileApi.StopPoint>, boardOrder: Int, boardName: String, bLat: Double, bLng: Double,
        alightName: String, aLat: Double, aLng: Double,
    ): List<ChelaileApi.StopPoint>? {
        val st = all.filter { usable(it) }
        if (st.size < 2) return null
        fun nearest(la: Double, ln: Double, from: Int): Int? {
            if (la.isNaN() || ln.isNaN() || from >= st.size) return null
            val i = (from until st.size).minByOrNull { Geo.distanceM(la, ln, st[it].lat, st[it].lng) } ?: return null
            return i.takeIf { Geo.distanceM(la, ln, st[i].lat, st[i].lng) <= 800 }
        }
        val b = st.indexOfFirst { boardOrder >= 0 && it.order == boardOrder }.takeIf { it >= 0 }
            ?: st.indexOfFirst { same(it.name, boardName) }.takeIf { it >= 0 }
            ?: nearest(bLat, bLng, 0)
            ?: return null
        val a = (b + 1 until st.size).firstOrNull { same(st[it].name, alightName) }
            ?: nearest(aLat, aLng, b + 1)
            ?: return null
        if (a <= b) return null
        return st.subList(b, a + 1).toList()
    }

    /** 沿线总长（米） */
    fun lengthM(stops: List<ChelaileApi.StopPoint>): Double =
        stops.zipWithNext { p, q -> Geo.distanceM(p.lat, p.lng, q.lat, q.lng) }.sum()

    /**
     * 当前位置投到最近的一段上。minSeg：不往回找（防抖，调用方传「到过的最远段 - 1」）。
     */
    fun snap(stops: List<ChelaileApi.StopPoint>, lat: Double, lng: Double, minSeg: Int = 0): Snap? {
        if (stops.size < 2) return null
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        val ky = 110_574.0
        val from = minSeg.coerceIn(0, stops.size - 2)
        var bestSeg = -1; var bestT = 0.0; var bestOff = Double.MAX_VALUE
        for (i in from until stops.size - 1) {
            val x1 = (stops[i].lng - lng) * kx; val y1 = (stops[i].lat - lat) * ky
            val x2 = (stops[i + 1].lng - lng) * kx; val y2 = (stops[i + 1].lat - lat) * ky
            val dx = x2 - x1; val dy = y2 - y1
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0.0) 0.0 else (-(x1 * dx + y1 * dy) / len2).coerceIn(0.0, 1.0)
            val px = x1 + t * dx; val py = y1 + t * dy
            val off = sqrt(px * px + py * py)
            if (off < bestOff) { bestOff = off; bestSeg = i; bestT = t }
        }
        if (bestSeg < 0) return null
        val segLen = Geo.distanceM(stops[bestSeg].lat, stops[bestSeg].lng, stops[bestSeg + 1].lat, stops[bestSeg + 1].lng)
        val after = lengthM(stops.subList(bestSeg + 1, stops.size))
        return Snap(bestSeg, bestT, bestOff, segLen * (1 - bestT) + after, stops.size - 1 - bestSeg)
    }
}
