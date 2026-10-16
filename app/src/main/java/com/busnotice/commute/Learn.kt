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
    private const val MIN_DAYS = 4

    // v3：每条是「哪天:几点上车」，一天只留一条；旧数据混了大量测试样本，整体作废
    private fun key(tripId: String) = "v3:$tripId"

    private data class Entry(val day: Long, val minute: Int)

    private fun entries(ctx: Context, tripId: String): List<Entry> =
        (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(tripId), "") ?: "")
            .split(',').mapNotNull { e ->
                val (d, m) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                Entry(d.toLongOrNull() ?: return@mapNotNull null, m.toIntOrNull() ?: return@mapNotNull null)
            }

    /** 只在「确认是真坐车」后调用（见 CommuteService.commitBoard）；同一天重复记只保留第一次 */
    fun record(ctx: Context, tripId: String, millis: Long) {
        val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val day = t.toLocalDate().toEpochDay()
        val list = entries(ctx, tripId)
        if (list.any { it.day == day }) return
        val out = (list + Entry(day, t.hour * 60 + t.minute)).takeLast(KEEP)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(tripId), out.joinToString(",") { "${it.day}:${it.minute}" }).apply()
    }

    /**
     * 这天通常几点上第一段车：同一个星期几攒够 3 天用它的中位数，
     * 否则全部攒够 5 天用总体中位数；都不够返回 null。
     */
    fun typicalBoard(ctx: Context, tripId: String, dow: Int): Int? {
        val all = entries(ctx, tripId)
        val day = all.filter { java.time.LocalDate.ofEpochDay(it.day).dayOfWeek.value == dow }.map { it.minute }
        if (day.size >= 3) return day.sorted()[day.size / 2]
        return if (all.size >= 5) all.map { it.minute }.sorted()[all.size / 2] else null
    }

    /** 按习惯推迟自动弹出：通常上车前 40 分钟弹，但不早于时间窗开始、不晚于结束前 15 分钟 */
    fun learnedStart(ctx: Context, trip: Trip, dow: Int): Int? {
        val b = typicalBoard(ctx, trip.id, dow) ?: return null
        val s = (b - 40).coerceAtMost(trip.endMinute - 15)
        return s.takeIf { it > trip.startMinute }
    }

    fun samples(ctx: Context, tripId: String): List<Int> = entries(ctx, tripId).map { it.minute }

    data class Suggestion(val start: Int, val end: Int, val typical: Int, val n: Int)

    /** 取 10%–90% 分位，前后各放 10 分钟，取整到 5 分钟；样本太少返回 null */
    fun suggest(minutes: List<Int>): Suggestion? {
        if (minutes.size < MIN_SAMPLES) return null
        // 去掉离中位数 90 分钟以外的（加班等偶发样本），不让它们把时间窗撑大
        val med = minutes.sorted()[minutes.size / 2]
        val s = minutes.filter { kotlin.math.abs(it - med) <= 90 }.sorted()
        if (s.size < MIN_SAMPLES) return null
        fun q(f: Double) = s[((s.size - 1) * f).toInt()]
        val start = ((q(0.1) - 10) / 5 * 5).coerceAtLeast(0)
        val end = (((q(0.9) + 10) + 4) / 5 * 5).coerceAtMost(23 * 60 + 55)
        return Suggestion(start, end, q(0.5), s.size)
    }

    fun suggest(ctx: Context, trip: Trip): Suggestion? {
        val e = entries(ctx, trip.id)
        if (e.map { it.day }.distinct().size < MIN_DAYS) return null
        return suggest(e.map { it.minute })?.takeIf { it.start != trip.startMinute || it.end != trip.endMinute }
    }

    /**
     * 后台无感调整时间窗：样本够了就直接改，不弹建议。
     * 每次最多挪 30 分钟，避免一两次异常把窗口一下拉走。返回改过的行程或 null。
     */
    fun autoAdjust(ctx: Context, trip: Trip): Trip? {
        val trip = TripStore.get(ctx, trip.id) ?: trip
        val sg = suggest(ctx, trip) ?: return null
        fun step(from: Int, to: Int) = from + (to - from).coerceIn(-30, 30)
        val ns = step(trip.startMinute, sg.start)
        val ne = step(trip.endMinute, sg.end).coerceAtLeast(ns + 30)
        if (ns == trip.startMinute && ne == trip.endMinute) return null
        val nt = trip.copy(startMinute = ns, endMinute = ne)
        TripStore.upsert(ctx, nt)
        val t = java.time.LocalDate.now()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("adj:${trip.id}", "${t.monthValue}/${t.dayOfMonth} 按习惯调整为 %02d:%02d–%02d:%02d"
                .format(ns / 60, ns % 60, ne / 60, ne % 60)).apply()
        return nt
    }

    fun lastAdjust(ctx: Context, tripId: String): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("adj:$tripId", null)

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

    // ---------- 公交沿线平均车速（米/分钟，含停站）：每次按定位到了下车站，按沿线距离 / 用时更新 ----------
    private const val BUS_SPEED = "learn_bus_speed"

    fun busSpeed(ctx: Context, tripId: String, legIdx: Int): Double? =
        ctx.getSharedPreferences(BUS_SPEED, Context.MODE_PRIVATE).getFloat("$tripId#$legIdx", 0f)
            .takeIf { it > 0f }?.toDouble()

    /** 指数平均，新样本占 30%；离谱的样本（太慢/太快）不要 */
    fun recordBusSpeed(ctx: Context, tripId: String, legIdx: Int, mPerMin: Double) {
        if (mPerMin.isNaN() || mPerMin !in 80.0..700.0) return
        val p = ctx.getSharedPreferences(BUS_SPEED, Context.MODE_PRIVATE)
        val k = "$tripId#$legIdx"
        val old = p.getFloat(k, 0f)
        val v = if (old <= 0f) mPerMin else old * 0.7 + mPerMin * 0.3
        p.edit().putFloat(k, v.toFloat()).apply()
    }

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
