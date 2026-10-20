package com.busnotice.commute

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * 常去地点采样：通勤以外的时间，每 60–120 分钟随机醒一次，只拿一次定位就走（不开前台服务、不上胶囊）。
 * 定位只交给 Places 存在本机，不写进导出的轨迹记录。正在跑行程时跳过（人在路上）。
 */
object PlaceSampler {
    private const val MIN_GAP = 60 * 60_000L
    private const val MAX_GAP = 120 * 60_000L

    private fun intent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7301, Intent(ctx, PlaceSampleReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 排下一次采样（覆盖已有的那个） */
    fun schedule(ctx: Context, fromNow: Long = MIN_GAP + Random.nextLong(MAX_GAP - MIN_GAP)) {
        runCatching {
            val am = ctx.getSystemService(AlarmManager::class.java)
            // 不需要准点：用非精确的 AllowWhileIdle，系统可以顺手合并唤醒，省电
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + fromNow, intent(ctx))
            ctx.getSharedPreferences("places", Context.MODE_PRIVATE).edit()
                .putLong("nextSample", System.currentTimeMillis() + fromNow).apply()
        }
    }

    /** App 打开 / 开机时调用：没排上（或排的已经过期很久）就排一个 */
    fun ensure(ctx: Context) {
        val next = ctx.getSharedPreferences("places", Context.MODE_PRIVATE).getLong("nextSample", 0L)
        if (next < System.currentTimeMillis() - 10 * 60_000L || next > System.currentTimeMillis() + MAX_GAP + 60_000L) schedule(ctx, 5 * 60_000L)
    }
}

class PlaceSampleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        PlaceSampler.schedule(ctx)
        if (CommuteService.runningTripId() != null) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val l = withTimeoutOrNull(9_000) { runCatching { Loc.current(ctx) }.getOrNull() }
                if (l != null && System.currentTimeMillis() - l.time < 3 * 60_000L) {
                    Places.sample(ctx, l.latitude, l.longitude, l.accuracy, l.time)
                }
            } finally { pending.finish() }
        }
    }
}
