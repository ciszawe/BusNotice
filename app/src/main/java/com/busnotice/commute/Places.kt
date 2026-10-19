package com.busnotice.commute

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * 本机学「家」和「公司」两个常去地点：只存在手机本地（SharedPreferences），不上传，不进导出记录。
 * 投票来源：
 *  1. 行程起点 / 终点：上午（12 点前）出发的行程，起点投「家」、终点投「公司」；下午/晚上的反过来；
 *  2. 半夜 0–5 点拿到的定位投「家」（每天最多一票）。
 * 同类的票 150 米内并成一个点（按精度加权的滑动中心），≥ 3 票且来自 ≥ 2 天才算确认。
 * 目前只用来显示状态，不参与任何行程判断。
 */
object Places {
    enum class Kind { HOME, WORK }
    enum class Label { HOME, WORK, ON_THE_WAY, UNKNOWN }

    private const val PREFS = "places"
    private const val MERGE_M = 150.0
    private const val NEED_VOTES = 3
    private const val NEED_DAYS = 2
    private const val MAX_CLUSTERS = 6
    private const val MAX_ACC = 200f

    private class Cluster(val kind: Kind, var lat: Double, var lng: Double, var w: Double, var votes: Int, val days: MutableSet<Long>) {
        val confirmed get() = votes >= NEED_VOTES && days.size >= NEED_DAYS
    }

    private var cache: MutableList<Cluster>? = null

    @Synchronized
    private fun load(ctx: Context): MutableList<Cluster> {
        cache?.let { return it }
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("clusters", null)
        val out = mutableListOf<Cluster>()
        runCatching {
            val a = JSONArray(raw ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val d = o.optJSONArray("days") ?: JSONArray()
                out += Cluster(Kind.valueOf(o.getString("k")), o.getDouble("lat"), o.getDouble("lng"), o.getDouble("w"),
                    o.getInt("v"), (0 until d.length()).map { d.getLong(it) }.toMutableSet())
            }
        }
        cache = out
        return out
    }

    @Synchronized
    private fun save(ctx: Context, l: List<Cluster>) {
        val a = JSONArray()
        l.forEach { c ->
            a.put(JSONObject().put("k", c.kind.name).put("lat", c.lat).put("lng", c.lng).put("w", c.w).put("v", c.votes)
                .put("days", JSONArray(c.days.sorted().takeLast(14))))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("clusters", a.toString()).apply()
    }

    private fun day(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    private fun hour(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).hour

    /** 投一票：精度太差（≥ 200 米）不收 */
    @Synchronized
    fun vote(ctx: Context, kind: Kind, lat: Double, lng: Double, acc: Float, at: Long = System.currentTimeMillis()) {
        if (acc >= MAX_ACC || lat.isNaN() || lng.isNaN()) return
        val l = load(ctx)
        val wt = 1.0 / acc.coerceAtLeast(15f)
        val c = l.filter { it.kind == kind }.minByOrNull { Geo.distanceM(lat, lng, it.lat, it.lng) }
            ?.takeIf { Geo.distanceM(lat, lng, it.lat, it.lng) <= MERGE_M }
        if (c != null) {
            val tw = c.w + wt
            c.lat = (c.lat * c.w + lat * wt) / tw
            c.lng = (c.lng * c.w + lng * wt) / tw
            c.w = tw; c.votes++; c.days += day(at)
        } else {
            l += Cluster(kind, lat, lng, wt, 1, mutableSetOf(day(at)))
            // 同类候选太多：去掉票最少的
            val same = l.filter { it.kind == kind }
            if (same.size > MAX_CLUSTERS) l.remove(same.minBy { it.votes })
        }
        save(ctx, l)
    }

    /** 行程起点/终点投票：start = 起点（否则终点），startedAt = 行程开始时刻（判断上午/下午） */
    fun voteTrip(ctx: Context, start: Boolean, startedAt: Long, lat: Double, lng: Double, acc: Float, at: Long) {
        val morning = hour(startedAt) < 12
        val kind = if (start == morning) Kind.HOME else Kind.WORK
        vote(ctx, kind, lat, lng, acc, at)
    }

    /** 随手拿到的定位：0–5 点之间的投「家」，每天最多一票 */
    fun passive(ctx: Context, lat: Double, lng: Double, acc: Float, at: Long) {
        if (hour(at) !in 0..4 || acc >= MAX_ACC) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = day(at)
        if (p.getLong("nightDay", -1L) == d) return
        p.edit().putLong("nightDay", d).apply()
        vote(ctx, Kind.HOME, lat, lng, acc, at)
    }

    private fun confirmed(ctx: Context, kind: Kind): Cluster? =
        load(ctx).filter { it.kind == kind && it.confirmed }.maxByOrNull { it.votes }

    /** 在确认地点 200 米 + min(精度, 150) 米内 → 家/公司；两个都确认了但都不在 → 路上；其余 → 未知 */
    @Synchronized
    fun classify(ctx: Context, lat: Double, lng: Double, acc: Float): Label {
        if (acc >= 500f) return Label.UNKNOWN
        val r = 200.0 + acc.coerceAtMost(150f)
        val hits = Kind.values().mapNotNull { k -> confirmed(ctx, k)?.let { k to Geo.distanceM(lat, lng, it.lat, it.lng) } }
        hits.filter { it.second <= r }.minByOrNull { it.second }?.let { return if (it.first == Kind.HOME) Label.HOME else Label.WORK }
        return if (hits.size == 2) Label.ON_THE_WAY else Label.UNKNOWN
    }

    /** 学习进度 0..3：两个地点里较慢的那个（不满 2 天最多算 2） */
    @Synchronized
    fun progress(ctx: Context): Int = Kind.values().minOf { k ->
        load(ctx).filter { it.kind == k }.maxOfOrNull { c ->
            if (c.confirmed) NEED_VOTES else c.votes.coerceAtMost(if (c.days.size >= NEED_DAYS) NEED_VOTES else NEED_VOTES - 1)
        } ?: 0
    }

    /** 单个地点的进度 0..3（首页分开显示「家」「公司」各学到哪了，不再只显示慢的那个） */
    fun progressOf(ctx: Context, k: Kind): Int = load(ctx).filter { it.kind == k }.maxOfOrNull { c ->
        if (c.confirmed) NEED_VOTES else c.votes.coerceAtMost(if (c.days.size >= NEED_DAYS) NEED_VOTES else NEED_VOTES - 1)
    } ?: 0

    fun bothConfirmed(ctx: Context) = Kind.values().all { confirmed(ctx, it) != null }

    @Synchronized
    fun reset(ctx: Context) {
        cache = mutableListOf()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun text(l: Label): String? = when (l) {
        Label.HOME -> "在家"
        Label.WORK -> "在公司"
        Label.ON_THE_WAY -> "路上"
        Label.UNKNOWN -> null
    }
}
