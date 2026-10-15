package com.busnotice.commute

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * 实时通知：Android 16 promoted ongoing，ColorOS 16 渲染成流体云胶囊。
 * 准入条件：ongoing、有标题、请求提升、标准样式（ProgressStyle）、不着色、无自定义视图。
 * 能定制的只有进度条：分段颜色、站点圆点、移动的小图标、两端图标。
 */
object LiveNotifier {
    const val CHANNEL_LIVE = "commute_live"
    const val CHANNEL_ALERT = "commute_alert_v2"
    const val LIVE_ID = 1001
    private const val ALERT_ID = 1002

    const val BLUE = 0xFF3D7BFF.toInt()
    private const val BLUE_SOFT = 0xFF9DB9FF.toInt()
    private const val ORANGE = 0xFFFF8A3D.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    // 地铁用绿色、地铁图标，和公交（蓝色、大巴图标）一眼区分
    const val GREEN = 0xFF1AAE6F.toInt()
    private const val GREEN_SOFT = 0xFF8FD9B6.toInt()

    /** 胶囊要显示的内容 */
    data class Visual(
        val title: String,
        val text: String,
        val short: String,
        val sub: String = "",
        val countdownTo: Long? = null,
        val progress: Int? = null,          // 0..100
        val stops: Int = 0,                 // 进度条上画几个站点圆点
        val urgent: Boolean = false,
        val trackerRes: Int = R.drawable.ic_bus,
        val endRes: Int = R.drawable.ic_stop,
        val metro: Boolean = false,
    )

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("commute_alert")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, "通勤实时胶囊", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "出发倒计时和到站信息（流体云）"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "该出门了提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "需要马上出发、或换乘紧张时横幅 + 震动"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
            }
        )
    }

    fun warming(ctx: Context, tripName: String): Notification =
        base(ctx, Visual(tripName, "正在定位、查询实时公交…", "定位中")).build()

    fun build(ctx: Context, v: Visual): Notification {
        val b = base(ctx, v)
        if (v.countdownTo != null && v.countdownTo > System.currentTimeMillis()) {
            b.setWhen(v.countdownTo).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            b.setShowWhen(false)
        }
        if (v.progress != null) {
            val p = v.progress.coerceIn(0, 100)
            val color = if (v.urgent) ORANGE else if (v.metro) GREEN else BLUE
            val soft = if (v.metro) GREEN_SOFT else BLUE_SOFT
            val style = NotificationCompat.ProgressStyle()
                .setStyledByProgress(true)
                .setProgressTrackerIcon(IconCompat.createWithResource(ctx, badge(v.trackerRes)))
                .setProgressEndIcon(IconCompat.createWithResource(ctx, v.endRes))
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(100).setColor(color))
                .setProgress(p)
            val n = v.stops.coerceIn(0, 10)
            for (i in 1 until n) {
                val pos = i * 100 / n
                style.addProgressPoint(NotificationCompat.ProgressStyle.Point(pos).setColor(if (pos <= p) WHITE else soft))
            }
            b.setStyle(style)
        } else {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(v.text))
        }
        return b.build()
    }

    /** 进度条上的移动图标换成彩色徽标（绿底地铁 / 蓝底公交） */
    private fun badge(res: Int) = when (res) {
        R.drawable.ic_metro -> R.drawable.ic_badge_metro
        R.drawable.ic_bus -> R.drawable.ic_badge_bus
        else -> res
    }

    fun update(ctx: Context, n: Notification) {
        if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            try {
                NotificationManagerCompat.from(ctx).notify(LIVE_ID, n)
            } catch (_: SecurityException) { }
        }
    }

    /** 需要马上出发 / 换乘紧张时，额外弹一条横幅并震动 */
    fun alert(ctx: Context, title: String, text: String, metro: Boolean = false, id: Int = ALERT_ID) {
        val n = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
            .setSmallIcon(if (metro) R.drawable.ic_metro else R.drawable.ic_bus)
            .setColor(if (metro) GREEN else BLUE)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(2 * 60_000L)
            .setContentIntent(openApp(ctx))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) { }
    }

    /** 后台启动被系统拦截时的兜底：普通提醒，点一下在前台启动 */
    fun fallback(ctx: Context, trip: Trip) {
        val pi = PendingIntent.getActivity(
            ctx, 7, Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_START_TRIP, trip.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_bus)
            .setColor(BLUE)
            .setContentTitle("${trip.name}：点一下开始")
            .setContentText("系统没让胶囊在后台自动启动，点这里启动")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ALERT_ID + 1, n)
        } catch (_: SecurityException) { }
    }

    private fun base(ctx: Context, v: Visual): NotificationCompat.Builder =
        NotificationCompat.Builder(ctx, CHANNEL_LIVE)
            .setSmallIcon(if (v.metro) R.drawable.ic_metro else R.drawable.ic_bus)
            .setColor(if (v.metro) GREEN else BLUE)
            .setContentTitle(v.title)
            .setContentText(v.text)
            .setSubText(v.sub.ifEmpty { null })
            .setShortCriticalText(v.short)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setRequestPromotedOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(openApp(ctx))
            .setDeleteIntent(actionIntent(ctx, ActionReceiver.ACTION_STOP)) // 划走 = 关闭
            .addAction(0, "已上车", actionIntent(ctx, ActionReceiver.ACTION_BOARDED))
            .addAction(0, "关闭", actionIntent(ctx, ActionReceiver.ACTION_STOP))

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(ctx: Context, action: String) = PendingIntent.getBroadcast(
        ctx, action.hashCode(), Intent(ctx, ActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
