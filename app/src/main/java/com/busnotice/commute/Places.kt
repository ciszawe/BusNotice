package com.busnotice.commute

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * 本机学「家」和「公司」两个常去地点：只存在手机本地（SharedPreferences），不上传，不进导出记录。
 * 投票来源：
 *  1. 行程起点 / 终点：上午（12 点前）出发的行程，起点投「家」、终点投「公司」；下午/晚上的反过来。
 *     终点不按下车站投（下车后还要走一段），只投起点；
 *  2. 半夜 0–5 点拿到的定位投「家」（每天最多一票）。
 *  3.（优先）全天随机采样（PlaceSampler，通勤以外每 1–2 小时一次）：不先贴标签，所有样本 150 米内聚成点；
 *     夜里（21 点–次日 7 点前）样本最多、且跨 ≥ 3 天的点 = 家；工作日白天（10–17 点前）样本最多、跨 ≥ 3 天的另一个点 = 公司。
 *     采样点只存本机，不进导出轨迹。采样学出来的就用采样的，没学出来才用 1、2 的投票。
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
        if (CommuteService.runningTripId() == null) sample(ctx, lat, lng, acc, at)
        if (hour(at) !in 0..4 || acc >= MAX_ACC) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = day(at)
        if (p.getLong("nightDay", -1L) == d) return
        p.edit().putLong("nightDay", d).apply()
        vote(ctx, Kind.HOME, lat, lng, acc, at)
    }

    // ---------- 全天随机采样 ----------
    private class Sample(val lat: Double, val lng: Double, val acc: Float, val t: Long)
    private class Spot(var lat: Double, var lng: Double, var w: Double) {
        val nightDays = mutableSetOf<Long>(); val workDays = mutableSetOf<Long>()
        var night = 0; var work = 0
    }
    private const val KEEP_DAYS = 21
    private const val MAX_SAMPLES = 800
    private const val NEED_SAMPLE_DAYS = 3
    private var samplesCache: MutableList<Sample>? = null
    private var derivedCache: Map<Kind, Spot>? = null
    private var derivedBest: Map<Kind, Int>? = null

    @Synchronized
    private fun samples(ctx: Context): MutableList<Sample> {
        samplesCache?.let { return it }
        val out = mutableListOf<Sample>()
        runCatching {
            val a = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("samples", null) ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONArray(i)
                out += Sample(o.getDouble(0), o.getDouble(1), o.getDouble(2).toFloat(), o.getLong(3))
            }
        }
        samplesCache = out
        return out
    }

    /** 收一个采样点：精度 ≥ 200 米、超过 3 分钟的旧定位、离上一个采样不到 20 分钟的都不收 */
    @Synchronized
    fun sample(ctx: Context, lat: Double, lng: Double, acc: Float, at: Long) {
        val now = System.currentTimeMillis()
        if (acc >= MAX_ACC || lat.isNaN() || lng.isNaN() || now - at > 3 * 60_000L) return
        val l = samples(ctx)
        if (l.isNotEmpty() && at - l.last().t < 20 * 60_000L) return
        l += Sample(lat, lng, acc, at)
        l.removeAll { now - it.t > KEEP_DAYS * 86_400_000L }
        while (l.size > MAX_SAMPLES) l.removeAt(0)
        val a = JSONArray()
        l.forEach { a.put(JSONArray().put(it.lat).put(it.lng).put(it.acc.toDouble()).put(it.t)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("samples", a.toString()).apply()
        derivedCache = null; derivedBest = null
    }

    /** 0 = 其他时间，1 = 夜里（算家），2 = 工作日白天（算公司） */
    private fun slot(t: Long): Int {
        val z = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
        val h = z.hour
        if (h >= 21 || h < 7) return 1
        if (z.dayOfWeek.value <= 5 && h in 10..16) return 2
        return 0
    }

    /** 夜里 21 点以后算「当天晚上」，跨天的同一晚只算一天 */
    private fun nightDay(t: Long): Long = day(t - 7 * 3_600_000L)

    @Synchronized
    private fun derive(ctx: Context): Map<Kind, Spot> {
        derivedCache?.let { return it }
        val spots = mutableListOf<Spot>()
        samples(ctx).sortedBy { it.acc }.forEach { s ->
            val wt = 1.0 / s.acc.coerceAtLeast(15f)
            val sp = spots.minByOrNull { Geo.distanceM(s.lat, s.lng, it.lat, it.lng) }
                ?.takeIf { Geo.distanceM(s.lat, s.lng, it.lat, it.lng) <= MERGE_M }
                ?: Spot(s.lat, s.lng, 0.0).also { spots += it }
            val tw = sp.w + wt
            sp.lat = (sp.lat * sp.w + s.lat * wt) / tw; sp.lng = (sp.lng * sp.w + s.lng * wt) / tw; sp.w = tw
            when (slot(s.t)) {
                1 -> { sp.night++; sp.nightDays += nightDay(s.t) }
                2 -> { sp.work++; sp.workDays += day(s.t) }
            }
        }
        val home = spots.filter { it.night > 0 }.maxByOrNull { it.night }
        val work = spots.filter { it !== home && it.work > 0 }.maxByOrNull { it.work }
        derivedBest = mapOf(Kind.HOME to (home?.nightDays?.size ?: 0), Kind.WORK to (work?.workDays?.size ?: 0))
        val out = mutableMapOf<Kind, Spot>()
        if (home != null && home.nightDays.size >= NEED_SAMPLE_DAYS && home.night >= 4) out[Kind.HOME] = home
        if (work != null && work.workDays.size >= NEED_SAMPLE_DAYS && work.work >= 4 &&
            (home == null || Geo.distanceM(work.lat, work.lng, home.lat, home.lng) > 300)) out[Kind.WORK] = work
        derivedCache = out
        return out
    }

    private fun sampleProgress(ctx: Context, k: Kind): Int {
        derive(ctx)
        return (derivedBest?.get(k) ?: 0).coerceAtMost(NEED_SAMPLE_DAYS)
    }

    /** 确认的地点：先用采样学出来的，没有才用投票的 */
    private fun confirmed(ctx: Context, kind: Kind): Pair<Double, Double>? {
        derive(ctx)[kind]?.let { return it.lat to it.lng }
        return load(ctx).filter { it.kind == kind && it.confirmed }.maxByOrNull { it.votes }?.let { it.lat to it.lng }
    }

    /** 在确认地点 200 米 + min(精度, 150) 米内 → 家/公司；两个都确认了但都不在 → 路上；其余 → 未知 */
    @Synchronized
    fun classify(ctx: Context, lat: Double, lng: Double, acc: Float): Label {
        if (acc >= 500f) return Label.UNKNOWN
        val r = 200.0 + acc.coerceAtMost(150f)
        val hits = Kind.values().mapNotNull { k -> confirmed(ctx, k)?.let { k to Geo.distanceM(lat, lng, it.first, it.second) } }
        hits.filter { it.second <= r }.minByOrNull { it.second }?.let { return if (it.first == Kind.HOME) Label.HOME else Label.WORK }
        return if (hits.size == 2) Label.ON_THE_WAY else Label.UNKNOWN
    }

    /** 学习进度 0..3：两个地点里较慢的那个（不满 2 天最多算 2） */
    @Synchronized
    fun progress(ctx: Context): Int = Kind.values().minOf { progressOf(ctx, it) }

    /** 单个地点的进度 0..3（首页分开显示「家」「公司」各学到哪了，不再只显示慢的那个） */
    @Synchronized
    fun progressOf(ctx: Context, k: Kind): Int {
        if (derive(ctx)[k] != null) return NEED_VOTES
        val v = load(ctx).filter { it.kind == k }.maxOfOrNull { c ->
            if (c.confirmed) NEED_VOTES else c.votes.coerceAtMost(if (c.days.size >= NEED_DAYS) NEED_VOTES else NEED_VOTES - 1)
        } ?: 0
        return maxOf(v, sampleProgress(ctx, k).coerceAtMost(NEED_VOTES - 1))
    }

    fun bothConfirmed(ctx: Context) = Kind.values().all { confirmed(ctx, it) != null }

    @Synchronized
    fun reset(ctx: Context) {
        cache = mutableListOf(); samplesCache = mutableListOf(); derivedCache = null; derivedBest = null
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun text(l: Label): String? = when (l) {
        Label.HOME -> "在家"
        Label.WORK -> "在公司"
        Label.ON_THE_WAY -> "路上"
        Label.UNKNOWN -> null
    }
}
