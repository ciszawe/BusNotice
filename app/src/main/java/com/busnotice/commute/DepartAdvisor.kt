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
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import kotlin.math.abs

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

    /** 当前这条出发建议（App 首页卡片和流体云共用），只存本机 */
    data class Advice(
        val day: Long, val tripId: String, val tripName: String, val line: String, val stop: String,
        val busAt: Long, val leaveAt: Long, val projected: Boolean, val missedLeaveAt: Long, val at: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().put("day", day).put("trip", tripId).put("name", tripName)
            .put("line", line).put("stop", stop).put("bus", busAt).put("leave", leaveAt)
            .put("proj", projected).put("missed", missedLeaveAt).put("at", at)
        companion object {
            fun from(o: JSONObject) = Advice(o.optLong("day"), o.optString("trip"), o.optString("name"),
                o.optString("line"), o.optString("stop"), o.optLong("bus"), o.optLong("leave"),
                o.optBoolean("proj"), o.optLong("missed"), o.optLong("at"))
        }
    }

    val current = MutableStateFlow<Advice?>(null)

    /** 今天还有效的建议（行程没开始、没被「今天不提醒」、目标车还没开走太久） */
    fun load(ctx: Context, now: Long = System.currentTimeMillis()): Advice? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = runCatching { Advice.from(JSONObject(p.getString("cur", null) ?: return null)) }.getOrNull() ?: return null
        val ok = a.day == LocalDate.now().toEpochDay() && p.getLong("mute", -1) != a.day &&
            !Scheduler.ranToday(ctx, a.tripId) && CommuteService.instance == null && now < a.busAt + 2 * 60_000L
        return a.takeIf { ok }.also { current.value = it }
    }

    /** 通知上点「今天不提醒」 */
    fun mute(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("mute", LocalDate.now().toEpochDay()).apply()
        clear(ctx)
    }

    /** 行程开始了（离开公司 / 手动开始）：建议交给胶囊本身，不再单独显示 */
    fun clear(ctx: Context) {
        current.value = null
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getLong("shown", -1) == LocalDate.now().toEpochDay() && CommuteService.instance == null) {
            runCatching { NotificationManagerCompat.from(ctx).cancel(LiveNotifier.LIVE_ID) }
        }
        runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID) }
        p.edit().putLong("shown", -1).apply()
    }

    /** 后台采样拿到某条线的实时后调用：更新出发建议，必要时发流体云 */
    fun onRealtime(ctx: Context, c: Candidate, rt: ChelaileApi.LineRealtime, now: Long = System.currentTimeMillis()) {
        runCatching {
            if (CommuteService.instance != null) return
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getLong("mute", -1) == today.toEpochDay()) return
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
                BusSampler.write(ctx, JSONObject().put("t", now).put("k", "depart_plan").put("trip", trip.name)
                    .put("bus", p.busAt).put("leave", p.leaveAt).put("proj", p.projected).put("lead", ld.total)
                    .put("first", ld.firstWalkMin).put("before", ld.beforeMin).put("xfer", ld.transferWalkMin).put("n", arr.size))

                val prev = runCatching { Advice.from(JSONObject(prefs.getString("cur", "") ?: "")) }.getOrNull()
                    ?.takeIf { it.day == today.toEpochDay() && it.tripId == trip.id }
                val sameBus = prev != null && abs(prev.busAt - p.busAt) < 3 * 60_000L
                // 上一条建议的出发时刻已经过了、目标换成了后一班 → 记为错过，卡片和胶囊上写出来
                val missed = when {
                    prev == null -> 0L
                    sameBus -> prev.missedLeaveAt
                    prev.leaveAt < now - 60_000L -> prev.leaveAt
                    else -> 0L
                }
                val a = Advice(today.toEpochDay(), trip.id, trip.name, c.name, c.stopName, p.busAt, p.leaveAt, p.projected, missed, now)
                prefs.edit().putString("cur", a.toJson().toString()).apply()
                current.value = a

                val inMin = ((p.leaveAt - now) / 60_000L).toInt()
                val shown = prefs.getLong("shown", -1) == today.toEpochDay()
                // 第一次进入出发前 15 分钟才弹；弹过之后一直跟着更新（错过了就换成下一班）
                if (inMin > NOTIFY_AHEAD_MIN && !shown) continue
                val loud = !sameBus && inMin <= NOTIFY_AHEAD_MIN
                showCapsule(ctx, a, now, loud)
                prefs.edit().putLong("shown", today.toEpochDay()).apply()
                BusSampler.write(ctx, JSONObject().put("t", now).put("k", "depart_notify")
                    .put("trip", trip.name).put("in", inMin).put("bus", p.busAt).put("loud", loud).put("missed", missed))
            }
        }
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    fun hm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HM)

    /** 卡片 / 胶囊共用的文案 */
    fun headline(a: Advice, now: Long): String {
        val inMin = ((a.leaveAt - now + 30_000L) / 60_000L).toInt()
        return when {
            inMin <= 0 -> "现在出发，刚好接上"
            else -> "约 $inMin 分钟后出发"
        }
    }

    fun detail(a: Advice): String =
        (if (a.missedLeaveAt > 0) "错过 ${hm(a.missedLeaveAt)} 那班 · " else "") +
            "${hm(a.leaveAt)} 出发，接 ${hm(a.busAt)} 到${a.stop}的 ${a.line}" + if (a.projected) "（按间隔推算）" else ""

    /** 流体云：和行程胶囊同一个通知位，行程一开始就被胶囊本身接替 */
    private fun showCapsule(ctx: Context, a: Advice, now: Long, loud: Boolean) {
        LiveNotifier.ensureChannels(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val mute = PendingIntent.getBroadcast(ctx, ACTION_MUTE.hashCode(),
            Intent(ctx, ActionReceiver::class.java).setAction(ACTION_MUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(ctx, if (loud) LiveNotifier.CHANNEL_POP else LiveNotifier.CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_bus).setColor(LiveNotifier.BLUE)
            .setContentTitle(headline(a, now)).setContentText(detail(a))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail(a)))
            .setShortCriticalText(if (a.leaveAt > now) "${hm(a.leaveAt)}走" else "该走了")
            .setOngoing(true).setRequestPromotedOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOnlyAlertOnce(!loud).setSilent(!loud)
            .setContentIntent(open)
            .addAction(0, "今天不提醒", mute)
            .setTimeoutAfter(maxOf(a.busAt - now, 0L) + 3 * 60_000L)
        if (a.leaveAt > now) b.setWhen(a.leaveAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        else b.setShowWhen(false)
        try { nm.cancel(NOTIF_ID); nm.notify(LiveNotifier.LIVE_ID, b.build()) } catch (_: SecurityException) { }
    }

    const val ACTION_MUTE = "com.busnotice.commute.DEPART_MUTE"
}
