package com.busnotice.commute

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import java.time.LocalDate

/**
 * 出发地的 Wi-Fi（只存本机，不写进轨迹记录）：
 * 「留意离开」时人在出发地范围内、连着某个 Wi-Fi，就给这个 Wi-Fi 记一天；满 2 天算出发地 Wi-Fi。
 * 之后在窗口内从这个 Wi-Fi 断开，就当作已经离开出发地，不用等走出定位半径（出发地离车站近时很有用）。
 */
object OfficeWifi {
    private const val PREFS = "leave_wifi"
    private const val NEED_DAYS = 2
    private const val FRESH_MS = 15 * 60_000L

    @SuppressLint("MissingPermission")
    fun current(ctx: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = ctx.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo ?: return null
        @Suppress("DEPRECATION")
        val s = info.ssid?.trim('"') ?: return null
        if (s.isEmpty() || s == "<unknown ssid>" || info.networkId == -1) null else s
    }.getOrNull()

    /** 人在出发地范围内时记一次 */
    fun observe(ctx: Context, planId: String, ssid: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "days_${planId}_$ssid"
        val today = LocalDate.now().toEpochDay()
        val days = (p.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        days += today.toString()
        // 只留最近 30 天
        days.removeAll { (it.toLongOrNull() ?: 0L) < today - 30 }
        p.edit().putStringSet(key, days).apply()
    }

    fun known(ctx: Context, planId: String, ssid: String): Boolean =
        (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("days_${planId}_$ssid", emptySet())?.size ?: 0) >= NEED_DAYS

    /** 窗口内最近一次确认「连着出发地 Wi-Fi」的时间 */
    fun markOn(ctx: Context, planId: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("on_$planId", System.currentTimeMillis()).apply()

    fun wasOnRecently(ctx: Context, planId: String): Boolean =
        System.currentTimeMillis() - ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("on_$planId", 0L) < FRESH_MS

    fun clearOn(ctx: Context, planId: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("on_$planId").apply()

    // ---------- 断开后先确认：可能只是切了流量 ----------

    /** 第一次发现出发地 Wi-Fi 断开的时刻和当时的累计步数（读不到步数记 -1） */
    fun markLost(ctx: Context, planId: String, at: Long, steps: Float) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("lost_$planId", at).putFloat("lostSteps_$planId", steps).apply()

    fun lost(ctx: Context, planId: String): Pair<Long, Float>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("lost_$planId", 0L)
        return if (at <= 0L) null else at to p.getFloat("lostSteps_$planId", -1f)
    }

    fun clearLost(ctx: Context, planId: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("lost_$planId").remove("lostSteps_$planId").apply()

    // ---------- 进程活着时，Wi-Fi 一断就马上看一次，不用等下一次轮询 ----------

    @Volatile private var cb: android.net.ConnectivityManager.NetworkCallback? = null

    /** 时间窗内确认连着出发地 Wi-Fi 时调用：登记一次 Wi-Fi 断开回调（进程被杀就失效，轮询兜底） */
    fun listen(ctx: Context, planId: String) {
        if (cb != null) return
        val app = ctx.applicationContext
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val req = android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build()
        val c = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onLost(network: android.net.Network) {
                BusSampler.write(app, org.json.JSONObject().put("t", System.currentTimeMillis()).put("k", "wifi_lost"))
                Scheduler.watchNow(app, planId)
            }
        }
        runCatching { cm.registerNetworkCallback(req, c); cb = c }
    }

    fun unlisten(ctx: Context) {
        val c = cb ?: return
        runCatching { ctx.applicationContext.getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(c) }
        cb = null
    }
}

/** 计步芯片的累计步数（开机以来，不依赖 App 活着）；读不到返回 null */
object StepCount {
    suspend fun read(ctx: Context): Float? {
        if (ctx.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val sm = ctx.getSystemService(android.hardware.SensorManager::class.java) ?: return null
        val s = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER) ?: return null
        val got = kotlinx.coroutines.CompletableDeferred<Float>()
        val l = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) { got.complete(e.values[0]) }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        return try {
            sm.registerListener(l, s, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
            kotlinx.coroutines.withTimeoutOrNull(3_000L) { got.await() }
        } catch (_: Exception) { null } finally { runCatching { sm.unregisterListener(l) } }
    }
}
