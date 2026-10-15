package com.busnotice.commute

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** App 界面读取的实时状态 */
data class LiveSnapshot(
    val tripId: String,
    val tripName: String,
    val legIndex: Int,
    val legCount: Int,
    val legTitle: String,
    val headline: String,
    val detail: String,
    val mode: Decision.Mode?,
    val progress: Int?,
    val countdownTo: Long?,
    val updatedAt: Long = System.currentTimeMillis(),
)

object LiveState {
    val snapshot = MutableStateFlow<LiveSnapshot?>(null)
}

/**
 * 只在通勤时间窗内运行的定位前台服务。
 * 一条行程可以有多段：当前段没上车 → 按步行时间算出发；上车后 → 预估到换乘点能赶上下一段哪班车。
 * 省电：离站远/还早时低频定位、慢轮询；快出发或在站台时才开高频 GPS。
 */
class CommuteService : Service() {

    companion object {
        private const val TAG = "CommuteService"
        private const val EXTRA_TRIP_ID = "tripId"
        private const val EXTRA_MANUAL = "manual"
        private const val MANUAL_RUN_MS = 30 * 60_000L
        private const val NEAR_STOP_M = 120.0
        @Volatile var instance: CommuteService? = null

        fun start(ctx: Context, tripId: String, manual: Boolean) {
            val i = Intent(ctx, CommuteService::class.java)
                .putExtra(EXTRA_TRIP_ID, tripId)
                .putExtra(EXTRA_MANUAL, manual)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CommuteService::class.java))
        }
    }

    private enum class Power { LOW, HIGH }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var lm: LocationManager? = null
    private var power: Power? = null
    @Volatile private var best: Location? = null

    private lateinit var trip: Trip
    private var legIdx = 0
    private var boardedAt = 0L          // 上一段上车时间
    private var nearSince = 0L          // 第一次走到当前段上车点附近的时间
    private var fastFixes = 0
    private var alertedKey: String? = null
    private val maxStops = HashMap<String, Int>()
    private val maxEta = HashMap<String, Int>()

    private val listener = LocationListener { loc -> onLocation(loc) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_TRIP_ID)
        val t = id?.let { TripStore.get(this, it) }
        LiveNotifier.ensureChannels(this)
        try {
            startForeground(LiveNotifier.LIVE_ID, LiveNotifier.warming(this, t?.name ?: "通勤"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // 没有后台定位权限时，系统不允许从后台启动定位前台服务
            Log.e(TAG, "startForeground 失败", e)
            Diag.mark(this, "定位服务被拦截（后台定位未设为始终允许？）")
            t?.let { LiveNotifier.fallback(this, it) }
            stopSelf(); return START_NOT_STICKY
        }
        if (t == null || t.legs.isEmpty()) { finish(); return START_NOT_STICKY }
        val manual = intent.getBooleanExtra(EXTRA_MANUAL, false)

        instance = this
        if (!manual) Diag.mark(this, "已自动启动")
        trip = t
        legIdx = 0; boardedAt = 0L; nearSince = 0L; fastFixes = 0; alertedKey = null
        maxStops.clear(); maxEta.clear()
        setPower(Power.HIGH) // 预热：先拿到一个准的位置
        loop?.cancel()
        loop = scope.launch { runLoop(deadline(t, manual)) }
        return START_NOT_STICKY
    }

    private fun deadline(t: Trip, manual: Boolean): Long {
        if (manual) return System.currentTimeMillis() + MANUAL_RUN_MS
        val end = LocalDate.now().atStartOfDay().plusMinutes(t.endMinute.toLong() + 5)
        // 多段行程：给后面几段留出乘车时间
        val extra = trip.legs.dropLast(1).sumOf { it.rideMin + 5 }
        return end.plusMinutes(extra.toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** 「已上车」按钮或速度判断：进入下一段，最后一段则结束 */
    fun onBoarded() {
        if (!::trip.isInitialized) { finish(); return }
        if (legIdx >= trip.legs.size - 1) { finish(); return }
        boardedAt = System.currentTimeMillis()
        legIdx++
        nearSince = 0L; fastFixes = 0; alertedKey = null
        maxStops.clear(); maxEta.clear()
        loop?.cancel()
        loop = scope.launch { runLoop(deadline(trip, false).coerceAtLeast(System.currentTimeMillis() + 20 * 60_000L)) }
    }

    private suspend fun runLoop(deadline: Long) {
        while (scope.isActive && System.currentTimeMillis() < deadline) {
            val wait = try { tick() } catch (e: Exception) {
                Log.w(TAG, "刷新失败", e); 20_000L
            }
            delay(wait)
        }
        finish()
    }

    /** 刷新一次，返回下次刷新的间隔 */
    private suspend fun tick(): Long {
        val leg = trip.legs[legIdx]
        val prev = trip.legs.getOrNull(legIdx - 1)
        val now = System.currentTimeMillis()
        val loc = best?.takeIf { it.accuracy < 200 && now - it.time < 120_000 }

        val dists = leg.candidates.map { c -> loc?.let { Geo.distanceM(it.latitude, it.longitude, c.lat, c.lng) } }
        val nearest = dists.filterNotNull().minOrNull()
        if (nearest != null && nearest <= NEAR_STOP_M && nearSince == 0L) nearSince = now

        // 换乘中：还在上一段车上，按「剩余乘车 + 换乘步行」估算到下一站的时间
        val expectArrive = if (boardedAt > 0 && prev != null) boardedAt + prev.rideMin * 60_000L else 0L
        val connecting = expectArrive > 0 && nearSince == 0L && (nearest == null || nearest > 300) &&
            now < expectArrive + 5 * 60_000L
        val connectSec = if (connecting) (((expectArrive - now) / 1000).toInt().coerceAtLeast(0) + leg.transferWalkMin * 60) else 0

        // 地铁没有 GPS：到过站附近两分钟后，视为已上车
        if (leg.isMetroOnly() && nearSince > 0 && now - nearSince > 120_000) {
            onBoarded(); return 1_000L
        }

        val secOfDay = LocalTime.now().toSecondOfDay()
        val options = withContext(Dispatchers.IO) {
            leg.candidates.mapIndexed { i, c ->
                async {
                    val walk = if (connecting) connectSec else dists[i]?.let { Decision.walkSeconds(it) } ?: 0
                    when (c.type) {
                        Candidate.Type.BUS -> {
                            val rt = runCatching {
                                ChelaileApi.lineRealtime(c, loc?.latitude ?: c.lat, loc?.longitude ?: c.lng)
                            }.getOrNull()
                            Decision.Option(c, walk,
                                rt?.buses?.map { Decision.Arrival(it.travelSec, it.stopsAway, it.busId) } ?: emptyList(),
                                rt?.tip ?: "查询失败")
                        }
                        Candidate.Type.SHUTTLE -> Decision.Option(c, walk, Decision.shuttleArrivals(c, secOfDay))
                        Candidate.Type.METRO -> Decision.Option(c, walk, emptyList())
                    }
                }
            }.awaitAll()
        }
        val a = Decision.decide(options, connecting)
        val v = visual(a, leg, connecting, expectArrive, now)
        LiveNotifier.update(this, LiveNotifier.build(this, v))
        LiveState.snapshot.value = LiveSnapshot(
            trip.id, trip.name, legIdx, trip.legs.size, leg.title(), v.short, v.text, a.mode, v.progress, v.countdownTo,
        )

        // 该走了 / 换乘紧张：横幅 + 震动，每班车只提醒一次
        val key = "${a.mode}|${a.option?.candidate?.label()}|${a.arrival?.busId}"
        if ((a.mode == Decision.Mode.LEAVE_NOW || a.mode == Decision.Mode.HURRY || a.mode == Decision.Mode.CONNECT_MISS) &&
            alertedKey != key) {
            alertedKey = key
            LiveNotifier.alert(this, "${a.headline} · ${a.option?.candidate?.label() ?: trip.name}", a.detail)
        }
        record(a, nearest)

        // 省电：快出发、在站台附近才开高频 GPS
        val hot = when (a.mode) {
            Decision.Mode.LEAVE_NOW, Decision.Mode.HURRY, Decision.Mode.AT_STOP -> true
            Decision.Mode.LEAVE_LATER -> a.leaveInSec < 300
            Decision.Mode.METRO -> nearest != null && nearest < 400
            else -> nearest != null && nearest < 300
        } || loc == null
        setPower(if (hot) Power.HIGH else Power.LOW)

        val soonest = a.arrival?.etaSec ?: Int.MAX_VALUE
        return when {
            a.mode == Decision.Mode.METRO -> 30_000L
            hot || soonest < 600 -> 20_000L
            else -> 40_000L
        }
    }

    private fun visual(a: Decision.Advice, leg: Leg, connecting: Boolean, expectArrive: Long, now: Long): LiveNotifier.Visual {
        val c = a.option?.candidate
        val title = when {
            c == null -> trip.name
            c.type == Candidate.Type.METRO -> "${c.label()} · ${c.stopName}"
            else -> "${c.label()} · ${c.stopName}"
        }
        val sub = if (trip.legs.size > 1) "${trip.name} · 第 ${legIdx + 1}/${trip.legs.size} 段" else trip.name
        val arr = a.arrival

        if (connecting) {
            val prev = trip.legs[legIdx - 1]
            val total = prev.rideMin * 60_000L
            val p = if (total > 0) (100 - (expectArrive - now) * 100 / total).toInt() else 50
            return LiveNotifier.Visual(title, a.detail, a.headline, sub,
                countdownTo = arr?.let { now + it.etaSec * 1000L }, progress = p, stops = 0,
                urgent = a.mode == Decision.Mode.CONNECT_MISS,
                trackerRes = if (prev.isMetroOnly()) R.drawable.ic_metro else R.drawable.ic_bus)
        }
        if (a.mode == Decision.Mode.METRO) {
            return LiveNotifier.Visual(title, a.detail, a.headline, sub, progress = null,
                trackerRes = R.drawable.ic_walk)
        }

        val countdown = when (a.mode) {
            Decision.Mode.LEAVE_LATER -> now + a.leaveInSec * 1000L
            else -> arr?.takeIf { it.etaSec >= 0 }?.let { now + it.etaSec * 1000L }
        }
        var progress: Int? = null
        var stops = 0
        if (arr != null) {
            val key = (c?.label() ?: "") + arr.busId
            if (arr.stopsAway >= 0) {
                val m = maxOf(maxStops[key] ?: 0, arr.stopsAway, 3).coerceAtMost(10)
                maxStops[key] = m
                stops = m
                progress = (m - arr.stopsAway.coerceAtMost(m)) * 100 / m
            } else if (arr.etaSec >= 0) {
                val m = maxOf(maxEta[key] ?: 0, arr.etaSec, 300)
                maxEta[key] = m
                progress = (m - arr.etaSec) * 100 / m
            }
        }
        return LiveNotifier.Visual(title, a.detail, a.headline, sub, countdown, progress, stops,
            urgent = a.mode == Decision.Mode.HURRY,
            trackerRes = R.drawable.ic_bus)
    }

    /** 每次刷新记一行，以后用来学习步行时间和到站误差 */
    private fun record(a: Decision.Advice, dist: Double?) {
        try {
            val f = File(filesDir, "polls.csv")
            if (!f.exists()) f.writeText("ts,tripId,leg,distM,line,busId,etaSec,stopsAway,mode\n")
            f.appendText("${System.currentTimeMillis()},${trip.id},$legIdx,${dist?.toInt() ?: ""}," +
                "${a.option?.candidate?.label() ?: ""},${a.arrival?.busId ?: ""}," +
                "${a.arrival?.etaSec ?: ""},${a.arrival?.stopsAway ?: ""},${a.mode}\n")
        } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun setPower(p: Power) {
        if (p == power) return
        power = p
        val m = lm ?: getSystemService(LocationManager::class.java).also { lm = it }
        try { m.removeUpdates(listener) } catch (_: Exception) { }
        val providers = m.allProviders.filter {
            when (p) {
                Power.HIGH -> it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER ||
                    it == LocationManager.FUSED_PROVIDER
                Power.LOW -> it == LocationManager.NETWORK_PROVIDER || it == LocationManager.FUSED_PROVIDER
            }
        }.ifEmpty { listOf(LocationManager.GPS_PROVIDER) }
        val interval = if (p == Power.HIGH) 3_000L else 20_000L
        val minDist = if (p == Power.HIGH) 0f else 15f
        providers.forEach { pr ->
            try {
                if (best == null) m.getLastKnownLocation(pr)?.let { onLocation(it) }
                if (m.isProviderEnabled(pr)) m.requestLocationUpdates(pr, interval, minDist, listener, Looper.getMainLooper())
            } catch (e: Exception) {
                Log.w(TAG, "定位源 $pr 不可用", e)
            }
        }
        Log.i(TAG, "定位档位 $p: $providers")
    }

    private fun onLocation(loc: Location) {
        val cur = best
        val fresher = cur == null || loc.time - cur.time > 15_000
        if (fresher || loc.accuracy <= cur!!.accuracy) best = loc

        // 到过上车点、现在速度很快 → 大概率上车了
        if (nearSince > 0 && loc.hasSpeed() && loc.speed > 6f) {
            fastFixes++
            if (fastFixes >= 3) onBoarded()
        } else if (loc.hasSpeed()) {
            fastFixes = 0
        }
    }

    private fun finish() {
        try { lm?.removeUpdates(listener) } catch (_: Exception) { }
        LiveState.snapshot.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { lm?.removeUpdates(listener) } catch (_: Exception) { }
        if (instance === this) instance = null
        LiveState.snapshot.value = null
        scope.cancel()
        super.onDestroy()
    }
}

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_BOARDED = "com.busnotice.commute.BOARDED"
        const val ACTION_STOP = "com.busnotice.commute.STOP"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val svc = CommuteService.instance
        when {
            intent.action == ACTION_BOARDED && svc != null -> svc.onBoarded()
            else -> CommuteService.stop(ctx)
        }
    }
}
