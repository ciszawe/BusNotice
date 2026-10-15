package com.busnotice.commute

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import java.time.LocalDate
import java.time.LocalTime

/**
 * 没打开 App、也不在计划时间窗里，但人到了上车站附近：
 * 用系统自带的「接近提醒」（不依赖谷歌服务）在第一段上车站周围画围栏，
 * 进入时如果正好是这趟行程平时的出行时段，就自动开始一趟全新的行程（不接中断前的进度）。
 */
object GeoWatch {
    private const val TAG = "GeoWatch"
    const val EXTRA_TRIP = "tripId"
    private const val RADIUS_M = 200f

    private fun can(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pi(ctx: Context, tripId: String, idx: Int, create: Boolean): PendingIntent? =
        PendingIntent.getBroadcast(
            ctx, (tripId + "#geo" + idx).hashCode(),
            Intent(ctx, GeoReceiver::class.java).putExtra(EXTRA_TRIP, tripId),
            // 系统要往里塞「进入/离开」标记，必须可变
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE or
                (if (create) 0 else PendingIntent.FLAG_NO_CREATE),
        )

    fun register(ctx: Context, trip: Trip) {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        unregister(ctx, trip.id)
        if (!trip.enabled || trip.dayMask == 0 || !can(ctx)) return
        val stops = trip.legs.firstOrNull()?.candidates.orEmpty()
            .filter { !it.lat.isNaN() && !it.lng.isNaN() }
            .distinctBy { "%.4f,%.4f".format(it.lat, it.lng) }.take(3)
        stops.forEachIndexed { i, c ->
            try {
                lm.addProximityAlert(c.lat, c.lng, RADIUS_M, -1, pi(ctx, trip.id, i, true)!!)
            } catch (e: SecurityException) { Log.w(TAG, "没有定位权限", e) }
        }
    }

    fun unregister(ctx: Context, tripId: String) {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        for (i in 0 until 3) pi(ctx, tripId, i, false)?.let { runCatching { lm.removeProximityAlert(it) }; it.cancel() }
    }

    /** 现在算不算这趟行程的出行时段：学到的通常上车时间 ±60 分钟，没学到就用时间窗前后各放 60 分钟 */
    fun inHabitBand(ctx: Context, trip: Trip): Boolean {
        val today = LocalDate.now()
        if (trip.dayMask and (1 shl (today.dayOfWeek.value - 1)) == 0) return false
        val t = LocalTime.now(); val m = t.hour * 60 + t.minute
        val typical = Learn.typicalBoard(ctx, trip.id, today.dayOfWeek.value)
        return if (typical != null) kotlin.math.abs(m - typical) <= 60
        else m >= trip.startMinute - 60 && m <= trip.endMinute + 60
    }
}

class GeoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)) return
        val id = intent.getStringExtra(GeoWatch.EXTRA_TRIP) ?: return
        val trip = TripStore.get(ctx, id) ?: return
        if (CommuteService.instance != null || !trip.enabled) return
        val p = ctx.getSharedPreferences("geo", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (p.getString(id, null) == today) return           // 同一趟一天只自动拉起一次
        if (!GeoWatch.inHabitBand(ctx, trip)) return
        p.edit().putString(id, today).apply()
        Diag.mark(ctx, "到了上车站附近，自动开始「${trip.name}」")
        try {
            CommuteService.start(ctx, id, manual = false, fresh = true)
        } catch (e: Exception) {
            Diag.mark(ctx, "系统不让后台启动：${e.javaClass.simpleName}")
            LiveNotifier.fallback(ctx, trip)
        }
    }
}
