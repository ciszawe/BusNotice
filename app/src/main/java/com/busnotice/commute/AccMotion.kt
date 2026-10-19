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

    /**
     * 「手机一直没动」跟踪：把最近窗口按时间增量喂进来，记下最后一次持续动（走路 ≥ 6 秒或类似坐车的晃动 ≥ 30 秒）的时刻，
     * 以及窗口连续覆盖（中间断档不超过 90 秒）从什么时候开始。只用来判断能不能沿用上一次定位。
     */
    class StillTracker {
        var coveredSince = 0L; private set
        var lastT = 0L; private set
        var movedAt = 0L; private set
        private var activeRun = 0
        private var vehicleRun = 0

        fun feed(ws: List<Win>) {
            for (w in ws.sortedBy { it.t }) {
                if (w.t <= lastT) continue
                if (lastT == 0L || w.t - lastT > MAX_GAP_MS) { coveredSince = w.t - WIN_MS; activeRun = 0; vehicleRun = 0 }
                when (classify(w)) {
                    // 走路或更剧烈（幅值 ≥ 1.0）都算「在动」
                    Kind.WALK, Kind.OTHER -> { activeRun++; vehicleRun = 0 }
                    Kind.VEHICLE -> { vehicleRun++; activeRun = 0 }
                    Kind.STILL -> { activeRun = 0; vehicleRun = 0 }
                }
                if (activeRun >= 3 || vehicleRun >= 15) movedAt = w.t
                lastT = w.t
            }
        }

        /** 从 t 起手机一直没动：窗口覆盖到 t 附近（60 秒内）、最近 90 秒还有窗口、t 之后没有持续动过 */
        fun stillSince(t: Long, now: Long): Boolean =
            coveredSince > 0 && coveredSince <= t + 60_000L && now - lastT < MAX_GAP_MS && movedAt < t
    }

    private const val MAX_GAP_MS = 90_000L

    /**
     * 地铁站里最后一次「走了几步」结束的时刻：连续 3 个窗口里有 2 个是走路就算（上车前跨进车门那几步也要抓到）。
     * 只看 (from, now] 之间；没有返回 null。
     */
    fun lastWalkEnd(ws: List<Win>, from: Long, now: Long): Long? {
        val l = ws.filter { it.t > from && it.t <= now }.sortedBy { it.t }
        var end: Long? = null
        for (i in l.indices) {
            if (classify(l[i]) != Kind.WALK) continue
            val walks = (maxOf(0, i - 2)..i).count { classify(l[it]) == Kind.WALK }
            if (walks >= 2) end = l[i].t
        }
        return end
    }

    /**
     * 列车开动：from 之后第一段「连续 spanMs 都在晃、没有走路」的开始时刻。
     * 实测：下扶梯约 60 秒也在晃，站台上站着等车静止和小晃交替；列车开起来是连续一两分钟的晃动。
     * 所以要求 90 秒里至少 80% 窗口在晃（非静止）、一个走路窗口都没有、中间不断档。
     * 只用加速度分不清「站台上坐着玩手机」和「坐在车上」，要配合基站切换 / 定位移动才当上车。
     */
    fun departureSince(ws: List<Win>, from: Long, now: Long, spanMs: Long = 90_000L): Long? {
        val l = ws.filter { it.t > from && it.t <= now }.sortedBy { it.t }
        for (i in l.indices) {
            val t0 = l[i].t - WIN_MS
            if (t0 + spanMs > now) return null
            val seg = l.subList(i, l.size).takeWhile { it.t <= t0 + spanMs }
            if (seg.size < spanMs / WIN_MS * 0.8) continue
            if (seg.any { classify(it) == Kind.WALK }) continue
            if (seg.zipWithNext().any { (a, b) -> b.t - a.t > GAP_MS }) continue
            if (seg.count { classify(it) != Kind.STILL } >= seg.size * 0.8) return t0
        }
        return null
    }
}
