package com.busnotice.commute

import android.content.Context
import java.time.Instant
import java.time.ZoneId

/**
 * 学下班（或任何不固定）出发时间：每次第一段上车记一笔「几点上的车」，
 * 攒够几次后给出建议的时间窗。只存在本机。
 */
object Learn {
    private const val PREFS = "learn"
    private const val KEEP = 30
    const val MIN_SAMPLES = 6

    fun record(ctx: Context, tripId: String, millis: Long) {
        val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val minute = t.hour * 60 + t.minute
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = (p.getString(tripId, "") ?: "").split(',').filter { it.isNotBlank() } + "$minute"
        val dk = "$tripId@${t.dayOfWeek.value}"
        val byDay = (p.getString(dk, "") ?: "").split(',').filter { it.isNotBlank() } + "$minute"
        p.edit().putString(tripId, list.takeLast(KEEP).joinToString(","))
            .putString(dk, byDay.takeLast(8).joinToString(",")).apply()
    }

    /**
     * 这天通常几点上第一段车：同一个星期几攒够 3 次用它的中位数，
     * 否则全部记录攒够 5 次用总体中位数；都不够返回 null。
     */
    fun typicalBoard(ctx: Context, tripId: String, dow: Int): Int? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = (p.getString("$tripId@$dow", "") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }
        if (day.size >= 3) return day.sorted()[day.size / 2]
        val all = samples(ctx, tripId)
        return if (all.size >= 5) all.sorted()[all.size / 2] else null
    }

    /** 按习惯推迟自动弹出：通常上车前 40 分钟弹，但不早于时间窗开始、不晚于结束前 15 分钟 */
    fun learnedStart(ctx: Context, trip: Trip, dow: Int): Int? {
        val b = typicalBoard(ctx, trip.id, dow) ?: return null
        val s = (b - 40).coerceAtMost(trip.endMinute - 15)
        return s.takeIf { it > trip.startMinute }
    }

    fun samples(ctx: Context, tripId: String): List<Int> =
        (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(tripId, "") ?: "")
            .split(',').mapNotNull { it.trim().toIntOrNull() }

    data class Suggestion(val start: Int, val end: Int, val typical: Int, val n: Int)

    /** 取 10%–90% 分位，前后各放 10 分钟，取整到 5 分钟；样本太少返回 null */
    fun suggest(minutes: List<Int>): Suggestion? {
        if (minutes.size < MIN_SAMPLES) return null
        val s = minutes.sorted()
        fun q(f: Double) = s[((s.size - 1) * f).toInt()]
        val start = ((q(0.1) - 10) / 5 * 5).coerceAtLeast(0)
        val end = (((q(0.9) + 10) + 4) / 5 * 5).coerceAtMost(23 * 60 + 55)
        return Suggestion(start, end, q(0.5), s.size)
    }

    fun suggest(ctx: Context, trip: Trip): Suggestion? =
        suggest(samples(ctx, trip.id))?.takeIf { it.start != trip.startMinute || it.end != trip.endMinute }

    // ---------- 每段实际坐多久（从上车到走近下一段上车点，减掉换乘步行） ----------
    private const val RIDE = "learn_ride2"  // v2：旧样本的上车时刻记早了，作废

    fun recordRide(ctx: Context, tripId: String, legIdx: Int, minutes: Int) {
        if (minutes !in 1..180) return
        val p = ctx.getSharedPreferences(RIDE, Context.MODE_PRIVATE)
        val k = "$tripId#$legIdx"
        val list = (p.getString(k, "") ?: "").split(',').filter { it.isNotBlank() } + "$minutes"
        p.edit().putString(k, list.takeLast(10).joinToString(",")).apply()
    }

    /** 攒够 3 次后用中位数，否则 null（用行程里手填的） */
    fun rideMin(ctx: Context, tripId: String, legIdx: Int): Int? {
        val l = (ctx.getSharedPreferences(RIDE, Context.MODE_PRIVATE).getString("$tripId#$legIdx", "") ?: "")
            .split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (l.size < 3) null else l.sorted()[l.size / 2]
    }

    fun rideSamples(ctx: Context, tripId: String, legIdx: Int): Int =
        (ctx.getSharedPreferences(RIDE, Context.MODE_PRIVATE).getString("$tripId#$legIdx", "") ?: "")
            .split(',').count { it.isNotBlank() }

    // ---------- 地铁推算的偏差：每次在地铁段手动点「已上车」，对比推算班次修正 ----------
    private const val METRO = "learn_metro"

    fun metroKey(c: Candidate, peak: Boolean) = "${c.stopName}|${c.name}|${c.metroDir}|${if (peak) "p" else "o"}"

    fun metroOffsetSec(ctx: Context, key: String): Int =
        ctx.getSharedPreferences(METRO, Context.MODE_PRIVATE).getInt(key, 0)

    /** residualSec：实际 - 推算（已折到 ±半个间隔）；指数平均，新样本占 30% */
    fun recordMetro(ctx: Context, key: String, residualSec: Int) {
        val p = ctx.getSharedPreferences(METRO, Context.MODE_PRIVATE)
        val old = p.getInt(key, 0)
        p.edit().putInt(key, (old * 0.7 + residualSec * 0.3).toInt()).apply()
    }

    fun resetMetro(ctx: Context, c: Candidate) {
        val p = ctx.getSharedPreferences(METRO, Context.MODE_PRIVATE)
        p.edit().remove(metroKey(c, true)).remove(metroKey(c, false)).apply()
    }
}
