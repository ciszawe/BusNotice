package com.busnotice.commute

/** 根据去程生成回程：段顺序倒过来，每路公交换成反方向、在原下车站上车 */
object Reverse {
    data class Result(val trip: Trip, val warnings: List<String>)

    /** 网络请求，需在 IO 线程调用 */
    fun build(src: Trip): Result {
        val warnings = mutableListOf<String>()
        val n = src.legs.size
        val legs = src.legs.reversed().mapIndexed { j, leg ->
            val cands = leg.candidates.mapNotNull { c -> reverseCandidate(c, warnings) }
            val walk = if (j == 0) leg.transferWalkMin else src.legs[n - j].transferWalkMin
            Leg(cands, rideMin = leg.rideMin, transferWalkMin = walk)
        }.filter { it.candidates.isNotEmpty() }
        val trip = Trip(
            name = if (src.name.contains("上班")) "下班" else src.name + "·回程",
            legs = legs, dayMask = src.dayMask,
            startMinute = 18 * 60, endMinute = 18 * 60 + 30, warmupMin = src.warmupMin,
        )
        return Result(trip, warnings)
    }

    private fun reverseCandidate(c: Candidate, warnings: MutableList<String>): Candidate? = when (c.type) {
        Candidate.Type.SHUTTLE -> { warnings += "${c.label()} 是自定义通勤车，回程请手动添加"; null }
        Candidate.Type.METRO -> {
            if (c.alightName.isEmpty()) { warnings += "${c.label()} 没填下车站，回程请手动添加"; null }
            else runCatching {
                val st = ChelaileApi.search(c.alightName).stations
                    .firstOrNull { it.isSubway && it.name == c.alightName }
                    ?: ChelaileApi.search(c.alightName).stations.first { it.isSubway }
                c.copy(stopName = st.name, lat = st.lat, lng = st.lng, alightName = c.stopName)
            }.getOrElse { warnings += "没找到地铁站「${c.alightName}」"; null }
        }
        Candidate.Type.BUS -> {
            if (c.alightName.isEmpty()) { warnings += "${c.label()} 没选下车站，回程请手动添加"; null }
            else runCatching {
                val fwd = ChelaileApi.lineDetail(c.lineId, c.cityId)
                val revId = fwd.reverseLineId.ifEmpty {
                    ChelaileApi.search(c.name, c.cityId).lines.firstOrNull { it.name == c.name }
                        ?.dirs?.firstOrNull { it.lineId != c.lineId }?.lineId ?: ""
                }
                require(revId.isNotEmpty())
                val rev = ChelaileApi.lineDetail(revId, c.cityId)
                val st = rev.stations.first { it.name == c.alightName }
                c.copy(
                    stopName = st.name, lat = st.lat, lng = st.lng, lineId = revId, stationId = st.sId,
                    endSn = rev.endSn, targetOrder = st.order, alightName = c.stopName,
                )
            }.getOrElse { warnings += "${c.label()} 反方向没找到「${c.alightName}」站"; null }
        }
    }
}
