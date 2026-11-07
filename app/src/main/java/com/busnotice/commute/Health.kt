package com.busnotice.commute

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.Manifest

/**
 * 检查版（ColorOS 17 前后对比用）：只记录，不改行为。
 * - 上次进程是怎么没的（系统留下的退出原因）
 * - 系统版本、几项关键权限的实际状态
 * - 屏幕亮灭 / 充电，用来分清「被冻住」是不是跟熄屏有关
 */
object Health {
    private const val PREFS = "health"

    fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        14 -> "FREEZER"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        15 -> "PACKAGE_STATE"
        16 -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "UNKNOWN_$r"
    }

    /** 把还没记过的退出原因写进行程记录（最多 5 条） */
    fun logExits(ctx: Context) = runCatching {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return@runCatching
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getLong("exitTs", 0L)
        val list = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).filter { it.timestamp > seen }
        list.sortedBy { it.timestamp }.forEach { e ->
            TraceLog.event("exit_info", "reason" to reasonName(e.reason), "sub" to subReason(e), "desc" to e.description?.take(120),
                "imp" to e.importance, "pssKb" to e.pss, "rssKb" to e.rss, "at" to e.timestamp, "status" to e.status)
        }
        list.maxOfOrNull { it.timestamp }?.let { p.edit().putLong("exitTs", it).apply() }
    }

    /** 隐藏字段：系统更细的原因码（拿不到就 null） */
    private fun subReason(e: ApplicationExitInfo): Int? =
        runCatching { ApplicationExitInfo::class.java.getMethod("getSubReason").invoke(e) as Int }.getOrNull()

    private fun prop(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun logSystem(ctx: Context) = runCatching {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val actm = ctx.getSystemService(ActivityManager::class.java)
        fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        TraceLog.event("sys",
            "sdk" to Build.VERSION.SDK_INT, "release" to Build.VERSION.RELEASE, "display" to Build.DISPLAY,
            "rom" to (prop("ro.build.version.oplusrom") ?: prop("ro.build.version.opporom")),
            "exact" to am?.canScheduleExactAlarms(),
            "bgLoc" to granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            "battOpt" to !(pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false),
            "notif" to nm?.areNotificationsEnabled(),
            "bgRestricted" to actm?.isBackgroundRestricted,
            "standby" to runCatching { ctx.getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket }.getOrNull(),
            "powerSave" to pm?.isPowerSaveMode)
    }

    fun screenOn(ctx: Context) = ctx.getSystemService(PowerManager::class.java)?.isInteractive == true

    fun plugged(ctx: Context): Boolean = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }.getOrDefault(false)

    /** 屏幕亮灭 / 插拔电源：服务活着时动态注册 */
    fun screenReceiver() = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val what = when (i.action) {
                Intent.ACTION_SCREEN_ON -> "screen_on"; Intent.ACTION_SCREEN_OFF -> "screen_off"
                Intent.ACTION_USER_PRESENT -> "unlock"
                Intent.ACTION_POWER_CONNECTED -> "power_on"; Intent.ACTION_POWER_DISCONNECTED -> "power_off"
                else -> return
            }
            TraceLog.event("dev", "what" to what)
        }
    }

    fun screenFilter() = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
    }
}

/**
 * 关键时刻（换乘等车、车 15 分钟内到）每分钟用精确闹钟把进程叫醒一次：
 * 记录显示熄屏后进程会被冻住 4–5 分钟，只有闹钟能叫醒（11/6 晚 21:05–21:25 胶囊一直停在「无车」）。
 */
object WakeAlarm {
    private const val ACTION = "com.busnotice.commute.WAKE"
    private var armedFor = 0L

    private fun pi(ctx: Context, at: Long, create: Boolean): PendingIntent? = PendingIntent.getBroadcast(
        ctx, 7301, Intent(ctx, WakeReceiver::class.java).setAction(ACTION).putExtra("at", at),
        PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE))

    fun arm(ctx: Context, at: Long) = runCatching {
        // 已经定了、还没到：不重复定（每次 tick 都会调）
        if (armedFor > System.currentTimeMillis() && armedFor <= at) return@runCatching
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return@runCatching
        val p = pi(ctx, at, true)!!
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        armedFor = at
    }

    fun cancel(ctx: Context) = runCatching {
        if (armedFor == 0L) return@runCatching
        pi(ctx, 0L, false)?.let { ctx.getSystemService(AlarmManager::class.java)?.cancel(it); it.cancel() }
        armedFor = 0L
    }

    internal fun fired() { armedFor = 0L }
}

class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        WakeAlarm.fired()
        val at = intent.getLongExtra("at", 0L)
        val late = if (at > 0) (System.currentTimeMillis() - at) / 1000 else null
        TraceLog.event("wake", "lateS" to late, "screen" to Health.screenOn(ctx), "alive" to (CommuteService.instance != null))
        CommuteService.instance?.onWakeAlarm()
    }
}
