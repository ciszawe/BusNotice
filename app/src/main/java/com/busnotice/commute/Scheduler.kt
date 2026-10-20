package com.busnotice.commute

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 用精确闹钟在每个时间窗开始前唤醒：不常驻后台，也就不怕被杀 */
object Scheduler {
    private const val TAG = "Scheduler"
    const val EXTRA_PLAN_ID = "tripId"
    const val EXTRA_WATCH = "watch"
    const val WATCH_EVERY_MS = 2 * 60_000L

    fun rescheduleAll(ctx: Context) {
        TripStore.all(ctx).forEach { schedule(ctx, it) }
        PlaceSampler.ensure(ctx)
    }

    fun schedule(ctx: Context, plan: Trip) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx, plan.id)
        am.cancel(pi)
        GeoWatch.register(ctx, plan)
        if (!plan.enabled || plan.dayMask == 0 || plan.legs.isEmpty()) return
        // 「离开某地才弹」：保存时正好在窗口里，一分钟后就开始留意
        if (plan.leaveMode() && inWindow(plan, LocalDateTime.now())) {
            scheduleWatch(ctx, plan, System.currentTimeMillis() + 60_000L)
        }
        val at = nextTrigger(plan, LocalDateTime.now(), ctx) ?: return
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
        Log.i(TAG, "${plan.name} 下次唤醒 $at")
    }

    fun cancel(ctx: Context, planId: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(alarmIntent(ctx, planId)); am.cancel(watchIntent(ctx, planId))
        GeoWatch.unregister(ctx, planId)
    }

    fun inWindow(plan: Trip, now: LocalDateTime): Boolean {
        val bit = 1 shl (now.dayOfWeek.value - 1)
        val m = now.hour * 60 + now.minute
        return plan.dayMask and bit != 0 && m >= plan.startMinute && m < plan.endMinute
    }

    /** 窗口内的下一次「看看离开没有」 */
    fun scheduleWatch(ctx: Context, plan: Trip, atMillis: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = watchIntent(ctx, plan.id)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }

    private fun watchIntent(ctx: Context, planId: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, planId.hashCode() + 1,
            Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_PLAN_ID, planId).putExtra(EXTRA_WATCH, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 下一次「时间窗开始 - 预热」的时刻；如果此刻正处于窗口内，返回 null 由调用方立即启动 */
    fun nextTrigger(plan: Trip, now: LocalDateTime, ctx: Context? = null): LocalDateTime? {
        for (d in 0..7) {
            val day: LocalDate = now.toLocalDate().plusDays(d.toLong())
            val bit = 1 shl (day.dayOfWeek.value - 1) // 周一=bit0
            if (plan.dayMask and bit == 0) continue
            val warm = if (plan.leaveMode()) 0 else plan.warmupMin
            // 学到了这天通常几点走：晚点再弹，不在公司白挂着胶囊
            val learned = if (ctx != null && !plan.leaveMode()) Learn.learnedStart(ctx, plan, day.dayOfWeek.value) else null
            val t = day.atStartOfDay().plusMinutes((learned ?: (plan.startMinute - warm)).toLong())
            if (t.isAfter(now)) return t
        }
        return null
    }

    fun nextTriggerText(plan: Trip, ctx: Context? = null): String {
        val t = nextTrigger(plan, LocalDateTime.now(), ctx) ?: return "未安排"
        val days = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val today = LocalDate.now()
        val dayText = when (t.toLocalDate()) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> days[t.dayOfWeek.value - 1]
        }
        if (plan.leaveMode()) {
            if (inWindow(plan, LocalDateTime.now())) return "正在留意你离开出发地"
            return "$dayText %02d:%02d 起留意离开出发地".format(t.hour, t.minute)
        }
        val learned = ctx != null && Learn.learnedStart(ctx, plan, t.dayOfWeek.value) != null
        return "$dayText %02d:%02d ${if (learned) "按你的习惯弹出" else "预热"}".format(t.hour, t.minute)
    }

    private fun alarmIntent(ctx: Context, planId: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, planId.hashCode(),
            Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_PLAN_ID, planId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(Scheduler.EXTRA_PLAN_ID) ?: return
        val plan = TripStore.get(ctx, id) ?: return
        if (!intent.getBooleanExtra(Scheduler.EXTRA_WATCH, false)) Scheduler.schedule(ctx, plan) // 排下一天
        if (plan.leaveMode()) {
            // 不固定的出发时间：粗定位一次，走出范围才启动胶囊，否则几分钟后再看
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try { watch(ctx, plan) } finally { pending.finish() }
            }
            return
        }
        Diag.mark(ctx, "闹钟已触发")
        start(ctx, plan)
    }

    private suspend fun watch(ctx: Context, plan: Trip) {
        if (CommuteService.instance != null) return
        val now = LocalDateTime.now()
        if (!Scheduler.inWindow(plan, now)) {
            Diag.mark(ctx, "${plan.name}：窗口内一直没离开出发地"); return
        }
        // 外层超时要比 Loc.current 里的 8 秒长，否则现取的位置永远拿不到；超过 2 分钟的旧位置不算数
        val loc = withTimeoutOrNull(12_000) { Loc.current(ctx) }
            ?.takeIf { System.currentTimeMillis() - it.time < 120_000 }
        val d = loc?.let { Geo.distanceM(it.latitude, it.longitude, plan.leaveLat, plan.leaveLng) }
        val slack = (loc?.accuracy ?: 0f).coerceAtMost(50f)
        if (d != null && d > plan.leaveRadiusM + slack) {
            Diag.mark(ctx, "已离开出发地，自动启动")
            withContext(Dispatchers.Main) { start(ctx, plan) }
        } else {
            if (loc == null) Diag.mark(ctx, "留意离开中：这次没拿到位置")
            Scheduler.scheduleWatch(ctx, plan, System.currentTimeMillis() + Scheduler.WATCH_EVERY_MS)
        }
    }

    private fun start(ctx: Context, plan: Trip) {
        try {
            CommuteService.start(ctx, plan.id, manual = false)
        } catch (e: Exception) {
            Diag.mark(ctx, "系统不让后台启动：${e.javaClass.simpleName}")
            LiveNotifier.fallback(ctx, plan)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.rescheduleAll(ctx)
    }
}

/** 记录最近一次自动启动发生了什么，首页显示，方便排查被系统拦截 */
object Diag {
    fun mark(ctx: Context, what: String) {
        TraceLog.event("diag", "msg" to what)
        val t = java.time.LocalDateTime.now()
        ctx.getSharedPreferences("diag", Context.MODE_PRIVATE).edit()
            .putString("last", "%d/%d %02d:%02d %s".format(t.monthValue, t.dayOfMonth, t.hour, t.minute, what)).apply()
    }

    fun last(ctx: Context): String? = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE).getString("last", null)
}
