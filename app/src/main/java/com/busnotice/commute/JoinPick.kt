package com.busnotice.commute

/** 半路上车：在这段地铁的途经站里找人现在贴着的那一站 */
object JoinPick {
    /**
     * stations：上车站 → 下车站（含两头）。返回离 (lat, lng) 最近、且在 radiusM 内的中间站下标（1..size-2）；
     * 最近的是上车站 / 下车站或都不够近，返回 null。
     */
    fun pick(stations: List<Pair<Double, Double>>, lat: Double, lng: Double, radiusM: Double): Int? {
        if (stations.size < 3) return null
        val d = stations.map { (a, b) -> Geo.distanceM(lat, lng, a, b) }
        val i = d.indices.minByOrNull { d[it] } ?: return null
        if (i == 0 || i == stations.size - 1 || d[i] > radiusM) return null
        return i
    }
}
