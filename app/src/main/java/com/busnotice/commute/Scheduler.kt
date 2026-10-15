package com.busnotice.commute

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 用精确闹钟在每个时间窗开始前唤醒：不常驻后台，也就不怕被杀 */
object Scheduler {
    private const val TAG = "Scheduler"
    const val EXTRA_PLAN_ID = "tripId"

    fun rescheduleAll(ctx: Context) {
        TripStore.all(ctx).forEach { schedule(ctx, it) }
    }

    fun schedule(ctx: Context, plan: Trip) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx, plan.id)
        am.cancel(pi)
        if (!plan.enabled || plan.dayMask == 0 || plan.legs.isEmpty()) return
        val at = nextTrigger(plan, LocalDateTime.now()) ?: return
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
        Log.i(TAG, "${plan.name} 下次唤醒 $at")
    }

    fun cancel(ctx: Context, planId: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarmIntent(ctx, planId))
    }

    /** 下一次「时间窗开始 - 预热」的时刻；如果此刻正处于窗口内，返回 null 由调用方立即启动 */
    fun nextTrigger(plan: Trip, now: LocalDateTime): LocalDateTime? {
        for (d in 0..7) {
            val day: LocalDate = now.toLocalDate().plusDays(d.toLong())
            val bit = 1 shl (day.dayOfWeek.value - 1) // 周一=bit0
            if (plan.dayMask and bit == 0) continue
            val t = day.atStartOfDay().plusMinutes((plan.startMinute - plan.warmupMin).toLong())
            if (t.isAfter(now)) return t
        }
        return null
    }

    fun nextTriggerText(plan: Trip): String {
        val t = nextTrigger(plan, LocalDateTime.now()) ?: return "未安排"
        val days = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val today = LocalDate.now()
        val dayText = when (t.toLocalDate()) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> days[t.dayOfWeek.value - 1]
        }
        return "$dayText %02d:%02d 预热".format(t.hour, t.minute)
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
        Diag.mark(ctx, "闹钟已触发")
        try {
            CommuteService.start(ctx, plan.id, manual = false)
        } catch (e: Exception) {
            Diag.mark(ctx, "系统不让后台启动：${e.javaClass.simpleName}")
            LiveNotifier.fallback(ctx, plan)
        }
        Scheduler.schedule(ctx, plan) // 排下一次
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
        val t = java.time.LocalDateTime.now()
        ctx.getSharedPreferences("diag", Context.MODE_PRIVATE).edit()
            .putString("last", "%d/%d %02d:%02d %s".format(t.monthValue, t.dayOfMonth, t.hour, t.minute, what)).apply()
    }

    fun last(ctx: Context): String? = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE).getString("last", null)
}
