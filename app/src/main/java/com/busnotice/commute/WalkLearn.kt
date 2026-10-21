package com.busnotice.commute

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 学步行（只存本机，这版只学不用）：
 *  - first：出发地 → 第一段上车点（家 → 公交站、公司 → 地铁站）
 *  - last ：最后一段下车 / 出站 → 目的地（地铁出站 → 公司、公交下车 → 家）
 * 每次记：用了多久、走了多远（定位轨迹）、其中真正在走路的秒数（加速度）、算出来的步速。
 * 攒够几次后拿来替换写死的 1.2 米/秒，和「走到车站要几分钟」。
 */
object WalkLearn {
    private const val PREFS = "walk_learn"
    private const val KEEP = 30

    data class Fix(val t: Long, val lat: Double, val lng: Double, val acc: Float)

    data class Sample(
        val day: Long, val kind: String, val durSec: Int, val pathM: Int, val walkSec: Int,
        val speed: Double?,          // 米/秒；轨迹或走路秒数太少、算出来不像走路时为 null
        val partial: Boolean,        // 起点不准（启动时人已经在走）：时长只是下限，步速仍可用
    ) {
        fun toJson(): JSONObject = JSONObject().put("day", day).put("kind", kind).put("dur", durSec).put("path", pathM)
            .put("walk", walkSec).put("spd", speed ?: JSONObject.NULL).put("partial", partial)

        companion object {
            fun fromJson(o: JSONObject) = Sample(
                o.optLong("day"), o.optString("kind"), o.optInt("dur"), o.optInt("path"), o.optInt("walk"),
                if (o.isNull("spd")) null else o.optDouble("spd"), o.optBoolean("partial"),
            )
        }
    }

    // ---------- 纯计算（单测覆盖） ----------

    /** 轨迹长度：只用精度 ≤ 50 米的点，相邻两点不到 8 米当原地抖动不计，单段超过 300 米当跳点不计 */
    fun pathM(fixes: List<Fix>): Double {
        val good = fixes.filter { it.acc <= 50f }.sortedBy { it.t }
        var sum = 0.0
        for (i in 1 until good.size) {
            val d = Geo.distanceM(good[i - 1].lat, good[i - 1].lng, good[i].lat, good[i].lng)
            if (d in 8.0..300.0) sum += d
        }
        return sum
    }

    /** 走路秒数：[from, to) 内被判成走路的加速度窗口数 × 2 秒 */
    fun walkSec(wins: List<AccMotion.Win>, from: Long, to: Long): Int =
        wins.count { it.t in from until to && AccMotion.classify(it) == AccMotion.Kind.WALK } * 2

    /** 开始走路的时刻：from 之后第一个走路窗口，且它之后 60 秒内六成以上都在走路；没有返回 null */
    fun walkStart(wins: List<AccMotion.Win>, from: Long, to: Long): Long? {
        val w = wins.filter { it.t in from until to }.sortedBy { it.t }
        for (x in w) {
            if (AccMotion.classify(x) != AccMotion.Kind.WALK) continue
            val next = w.filter { it.t >= x.t && it.t < x.t + 60_000L }
            if (next.size >= 10 && next.count { AccMotion.classify(it) == AccMotion.Kind.WALK } >= next.size * 0.6) return x.t
        }
        return null
    }

    /** 到达时刻：从最后一个点往前，连续落在终点 60 米内的最早那个点 */
    fun arriveAt(fixes: List<Fix>, lat: Double, lng: Double, r: Double = 60.0): Long? {
        val s = fixes.sortedBy { it.t }
        var at: Long? = null
        for (f in s.asReversed()) {
            if (Geo.distanceM(f.lat, f.lng, lat, lng) <= r) at = f.t else break
        }
        return at
    }

    /** 组一个样本；时长不在 30 秒–40 分钟之间返回 null。步速要求走路 ≥ 60 秒、轨迹 ≥ 50 米、落在 0.5–2.5 米/秒 */
    fun build(day: Long, kind: String, start: Long, end: Long, fixes: List<Fix>, wins: List<AccMotion.Win>, partial: Boolean): Sample? {
        val dur = ((end - start) / 1000).toInt()
        if (dur !in 30..40 * 60) return null
        val path = pathM(fixes.filter { it.t in start..end })
        val walk = walkSec(wins, start, end)
        val spd = if (walk >= 60 && path >= 50) (path / walk).takeIf { it in 0.5..2.5 } else null
        return Sample(day, kind, dur, path.toInt(), walk, spd?.let { Math.round(it * 100) / 100.0 }, partial)
    }

    fun median(xs: List<Double>): Double? = xs.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    // ---------- 本机存取 ----------

    private fun key(tripId: String, kind: String) = "v1:$tripId:$kind"

    fun samples(ctx: Context, tripId: String, kind: String): List<Sample> = runCatching {
        val arr = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(tripId, kind), "[]"))
        (0 until arr.length()).map { Sample.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** 同一趟同一种一天只记一次（重新开始等情况不重复记） */
    fun record(ctx: Context, tripId: String, s: Sample) {
        val list = samples(ctx, tripId, s.kind)
        if (list.any { it.day == s.day }) return
        val arr = JSONArray(); (list + s).takeLast(KEEP).forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(tripId, s.kind), arr.toString()).apply()
    }

    /** 学到的步速（所有行程、所有步行段合起来，至少 3 次）；不够返回 null。这版还没有地方用它 */
    fun learnedSpeed(ctx: Context): Double? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = p.all.keys.filter { it.startsWith("v1:") }.flatMap { k ->
            val parts = k.split(':'); if (parts.size < 3) emptyList() else samples(ctx, parts[1], parts[2])
        }.mapNotNull { it.speed }
        return if (all.size >= 3) median(all) else null
    }
}
