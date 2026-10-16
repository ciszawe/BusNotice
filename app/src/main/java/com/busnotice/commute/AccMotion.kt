package com.busnotice.commute

/**
 * 加速度活动分类：纯规则，不依赖 Android，可单测。
 * 输入是 TraceLog 每 2 秒一条的线性加速度窗口（幅值均值 m、标准差 sd）。
 * 实测：静止 <0.15；坐列车 0.15–0.8；走路 1.5–2.5（sd 约 0.4–0.8）；快走/小跑 4–11。
 * 只当下车判断的一个佐证，不单独用。
 */
object AccMotion {
    enum class Kind { STILL, VEHICLE, WALK, OTHER }

    /** t = 窗口结束时刻（毫秒） */
    data class Win(val t: Long, val m: Double, val sd: Double)

    const val WIN_MS = 2_000L
    private const val GAP_MS = 6_000L     // 相邻窗口间隔超过这个 = 传感器断档，连续段到此为止

    fun classify(w: Win): Kind = when {
        w.m < 0.15 -> Kind.STILL
        w.m < 1.0 -> Kind.VEHICLE
        // 走路：幅值够大且比较稳（sd 小于均值）；1.0–1.2 之间说不清，不算
        w.m >= 1.2 && w.sd < w.m -> Kind.WALK
        else -> Kind.OTHER
    }

    /**
     * 最近一段持续走路从什么时候开始：最后一个走路窗口在 now 前 15 秒内，
     * 往前连续的走路窗口（中间最多夹 1 个不是走路的窗口）覆盖 ≥ minMs；没有返回 null。
     */
    fun walkingSustainedSince(ws: List<Win>, now: Long, minMs: Long = 20_000L): Long? {
        val l = ws.filter { it.t <= now + 5_000 }.sortedBy { it.t }
        val e = l.indexOfLast { classify(it) == Kind.WALK }
        if (e < 0 || l[e].t < now - 15_000) return null
        var start = e
        var skipped = 0
        var j = e - 1
        while (j >= 0) {
            if (l[j + 1].t - l[j].t > GAP_MS) break
            if (classify(l[j]) == Kind.WALK) start = j
            else if (++skipped > 1) break
            j--
        }
        val from = l[start].t - WIN_MS
        return if (l[e].t - from >= minMs) from else null
    }

    /** t 之前 lookbackMs 内有没有一段 ≥ minMs 的连续静止（列车停稳、开门前） */
    fun hadStillBefore(ws: List<Win>, t: Long, minMs: Long = 8_000L, lookbackMs: Long = 90_000L): Boolean {
        val l = ws.filter { it.t in (t - lookbackMs)..(t + WIN_MS) }.sortedBy { it.t }
        var runStart = -1L
        var prevT = -1L
        for (w in l) {
            val still = classify(w) == Kind.STILL
            if (!still || (prevT >= 0 && w.t - prevT > GAP_MS)) runStart = -1L
            if (still) {
                if (runStart < 0) runStart = w.t - WIN_MS
                if (w.t - runStart >= minMs) return true
            }
            prevT = w.t
        }
        return false
    }
}
