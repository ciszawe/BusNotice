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
}
