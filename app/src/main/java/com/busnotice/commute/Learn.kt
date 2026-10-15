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
        p.edit().putString(tripId, list.takeLast(KEEP).joinToString(",")).apply()
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
}
