package com.busnotice.commute

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 出发提醒（第一版，粗估）：行程还没开始、在时间窗里时，
 * 用换乘站公交的实时到站 − 前面几段要花的时间，反推「几点出发刚好接上」。
 *
 * 前面几段的用时优先用本机学到的（首段步行中位数、地铁乘车中位数），没学够就用行程里填的；
 * 实时只看得到正在路上的几辆车，再往后的班次按这几辆车的间隔往后推（标成「推算」）。
 * 只在「最早出发时刻 = max(现在, 时间窗开始)」之后找车；同一班车只提醒一次，换了目标班次才更新。
 */
object DepartAdvisor {
    const val NOTIF_ID = 7502
    private const val PREFS = "depart_advice"
    private const val BUFFER_MIN = 2          // 留给开门、等电梯之类的余量
    private const val METRO_WAIT_MIN = 3      // 地铁平均等车
    private const val DEFAULT_FIRST_WALK_MIN = 5
    private const val NOTIFY_AHEAD_MIN = 15   // 出发前多久开始提醒
    private const val DEFAULT_HEADWAY_MIN = 15

    data class Lead(val firstWalkMin: Int, val beforeMin: Int, val transferWalkMin: Int) {
        val total get() = firstWalkMin + beforeMin + transferWalkMin + BUFFER_MIN
    }

    data class Plan(val busAt: Long, val leaveAt: Long, val projected: Boolean)

    /**
     * 纯逻辑：给出当前看得到的车到站时刻（毫秒，升序前已乱序也行），算出最近一个能接上的班次。
     * earliest = 最早能出发的时刻；horizonMs 之外不推算。
     */
    fun plan(arrivals: List<Long>, leadMin: Int, earliest: Long, horizonMs: Long = 90 * 60_000L): Plan? {
        val lead = leadMin * 60_000L
        val seen = arrivals.filter { it > 0 }.sorted().distinct()
        seen.firstOrNull { it - lead >= earliest }?.let { return Plan(it, it - lead, projected = false) }
        if (seen.isEmpty()) return null
        val gaps = seen.zipWithNext { a, b -> b - a }.filter { it >= 3 * 60_000L }
        val headway = gaps.sorted().let { if (it.isEmpty()) DEFAULT_HEADWAY_MIN * 60_000L else it[it.size / 2] }
        var t = seen.last()
        while (t - lead < earliest) {
            t += headway
            if (t - seen.last() > horizonMs) return null
        }
        return Plan(t, t - lead, projected = true)
    }

    /** 换乘公交之前的段要花多久（不含公交这段） */
    fun lead(ctx: Context, trip: Trip, busLeg: Int): Lead {
        val first = WalkLearn.samples(ctx, trip.id, "first").map { it.durSec / 60.0 }
            .let { WalkLearn.median(it) }?.let { Math.round(it).toInt() } ?: DEFAULT_FIRST_WALK_MIN
        var before = 0
        for (i in 0 until busLeg) {
            val leg = trip.legs[i]
            if (i > 0) before += leg.transferWalkMin
            before += (Learn.rideMin(ctx, trip.id, i) ?: leg.rideMin) + if (leg.isMetroOnly()) METRO_WAIT_MIN else 0
        }
        return Lead(first, before, trip.legs[busLeg].transferWalkMin)
    }

    /** 第一段公交在第几段（至少要前面有段，第一段就是公交的行程由原来的逻辑管） */
    fun busLegOf(trip: Trip): Int? =
        trip.legs.indexOfFirst { l -> l.candidates.any { it.type == Candidate.Type.BUS && it.lineId.isNotBlank() } }
            .takeIf { it >= 1 }

    /** 后台采样拿到某条线的实时后调用：判断要不要发 / 更新出发提醒 */
    fun onRealtime(ctx: Context, c: Candidate, rt: ChelaileApi.LineRealtime, now: Long = System.currentTimeMillis()) {
        runCatching {
            if (CommuteService.instance != null) return
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val nowMin = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).let { it.hour * 60 + it.minute }
            for (trip in TripStore.all(ctx)) {
                if (!trip.enabled || Scheduler.ranToday(ctx, trip.id)) continue
                if (trip.dayMask and (1 shl (today.dayOfWeek.value - 1)) == 0) continue
                if (nowMin < trip.startMinute - NOTIFY_AHEAD_MIN || nowMin >= trip.endMinute) continue
                val bl = busLegOf(trip) ?: continue
                if (trip.legs[bl].candidates.none { it.lineId == c.lineId && it.stationId == c.stationId }) continue
                val ld = lead(ctx, trip, bl)
                val startMs = today.atStartOfDay(zone).toInstant().toEpochMilli() + trip.startMinute * 60_000L
                val arr = rt.buses.filter { it.travelSec >= 0 }.map { now + it.travelSec * 1000L }
                val p = plan(arr, ld.total, maxOf(now, startMs)) ?: continue
                val inMin = ((p.leaveAt - now) / 60_000L).toInt()
                BusSampler.write(ctx, org.json.JSONObject().put("t", now).put("k", "depart_plan").put("trip", trip.name)
                    .put("bus", p.busAt).put("leave", p.leaveAt).put("proj", p.projected).put("lead", ld.total)
                    .put("first", ld.firstWalkMin).put("before", ld.beforeMin).put("xfer", ld.transferWalkMin).put("n", arr.size))
                if (inMin > NOTIFY_AHEAD_MIN) continue
                notify(ctx, trip, c, p, inMin)
            }
        }
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HM)

    private fun notify(ctx: Context, trip: Trip, c: Candidate, p: Plan, inMin: Int) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = LocalDate.now().toEpochDay()
        val lastBus = if (prefs.getLong("day", -1) == day) prefs.getLong("bus", 0) else 0
        // 同一班车（前后 3 分钟内算同一班）不重复响，只静默更新文字
        val same = lastBus > 0 && kotlin.math.abs(lastBus - p.busAt) < 3 * 60_000L
        prefs.edit().putLong("day", day).putLong("bus", p.busAt).apply()
        val title = if (inMin <= 0) "现在出发，刚好接上 ${c.name}" else "约 $inMin 分钟后出发（${hm(p.leaveAt)}）"
        val text = "${c.name} 预计 ${hm(p.busAt)} 到 ${c.stopName}" + if (p.projected) " · 按间隔推算" else ""
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        LiveNotifier.ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, LiveNotifier.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(title).setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true).setSilent(same)
            .setAutoCancel(true)
            .setTimeoutAfter(maxOf(p.leaveAt - System.currentTimeMillis(), 0L) + 5 * 60_000L)
            .setContentIntent(open)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) } catch (_: SecurityException) { }
        BusSampler.write(ctx, org.json.JSONObject().put("t", System.currentTimeMillis()).put("k", "depart_notify")
            .put("trip", trip.name).put("in", inMin).put("bus", p.busAt).put("silent", same))
    }
}
