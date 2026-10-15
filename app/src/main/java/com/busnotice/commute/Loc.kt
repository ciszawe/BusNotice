package com.busnotice.commute

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

object Loc {
    private val exec = Executors.newSingleThreadExecutor()

    /** 尽快拿一个当前位置：先用 8 秒内的新鲜定位，失败退回最近一次已知位置 */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Location? {
        val m = ctx.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 60_000) return last
        val p = providers.firstOrNull() ?: return last
        val fresh = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                try {
                    m.getCurrentLocation(p, null, exec) { cont.resume(it) }
                } catch (e: Exception) { cont.resume(null) }
            }
        }
        return fresh ?: last
    }
}
