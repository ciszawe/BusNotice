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
    private var manualRun = false
    private var riding = false          // 最后一段已上车，正在去下车站
    private var transferAlerted = false
    private var arriveAlertedAt = 0L
    @Volatile private var alight: Pair<Double, Double>? = null
    private val metroDirs = java.util.concurrent.ConcurrentHashMap<String, List<ChelaileApi.SubwayDir>>()
    private val routes = java.util.concurrent.ConcurrentHashMap<String, List<Pair<Double, Double>>>() // lineId → 沿线站点
    private val speeds = ArrayDeque<Float>()
    private val routeHits = ArrayDeque<Boolean>()
    @Volatile private var lastGpsFix: Location? = null

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
        manualRun = manual; riding = false; transferAlerted = false; arriveAlertedAt = 0L
        speeds.clear(); routeHits.clear(); lastGpsFix = null
        setPower(Power.HIGH) // 预热：先拿到一个准的位置
        scope.launch(Dispatchers.IO) { prefetch(t) }
        loop?.cancel()
        loop = scope.launch { runLoop(deadline(t, manual)) }
        return START_NOT_STICKY
    }

    private fun deadline(t: Trip, manual: Boolean): Long {
        val now = System.currentTimeMillis()
        val rideTotal = t.legs.sumOf { it.rideMin + it.transferWalkMin } + 20
        if (manual) return now + maxOf(MANUAL_RUN_MS, rideTotal * 60_000L)
        // 「离开某地才弹」：启动时刻不固定，从现在起留足整段路程
        if (t.leaveMode()) return now + (rideTotal + 30) * 60_000L
        val end = LocalDate.now().atStartOfDay().plusMinutes(t.endMinute.toLong() + 5)
        // 多段行程：给后面几段留出乘车时间
        val extra = trip.legs.dropLast(1).sumOf { it.rideMin + 5 }
        return end.plusMinutes(extra.toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** 「已上车」按钮或速度判断：进入下一段；最后一段则进入「去下车站」，再按一次结束 */
    fun onBoarded() {
        if (!::trip.isInitialized) { finish(); return }
        val now = System.currentTimeMillis()
        if (legIdx == 0 && boardedAt == 0L) Learn.record(this, trip.id, now)
        if (legIdx >= trip.legs.size - 1) {
            if (riding || alight == null) { finish(); return }
            riding = true; boardedAt = now; arriveAlertedAt = 0L
            speeds.clear(); routeHits.clear()
            loop?.cancel()
            loop = scope.launch { runLoop(now + (trip.legs.last().rideMin + 20) * 60_000L) }
            return
        }
        boardedAt = now
        legIdx++
        nearSince = 0L; fastFixes = 0; alertedKey = null; transferAlerted = false
        maxStops.clear(); maxEta.clear(); speeds.clear(); routeHits.clear()
        loop?.cancel()
        loop = scope.launch { runLoop(deadline(trip, false).coerceAtLeast(now + 20 * 60_000L)) }
    }

    /** 后台预取：沿线站点（判断是否在车上）和最后一段的下车站坐标（到站提醒） */
    private fun prefetch(t: Trip) {
        t.legs.flatMap { it.candidates }.filter { it.lineId.isNotEmpty() }.distinctBy { it.lineId }.forEach { c ->
            runCatching { ChelaileApi.lineDetail(c.lineId, c.cityId) }.getOrNull()?.let { d ->
                routes[c.lineId] = d.stations.map { it.lat to it.lng }
            }
        }
        val last = t.legs.lastOrNull()?.candidates ?: return
        alight = last.firstNotNullOfOrNull { c ->
            when {
                !c.alightLat.isNaN() && !c.alightLng.isNaN() -> c.alightLat to c.alightLng
                c.alightName.isEmpty() -> null
                c.lineId.isNotEmpty() -> runCatching { ChelaileApi.lineDetail(c.lineId, c.cityId) }.getOrNull()
                    ?.stations?.firstOrNull { it.name == c.alightName }?.let { it.lat to it.lng }
                else -> null
            }
        } ?: last.firstOrNull { it.alightName.isNotEmpty() }?.let { c ->
            runCatching { ChelaileApi.search(c.alightName, c.cityId).stations }.getOrNull()
                ?.firstOrNull { it.name.startsWith(c.alightName) && (it.isSubway == c.isMetro()) }
                ?.let { it.lat to it.lng }
        }
    }

    private fun onRoute(loc: Location, leg: Leg): Boolean {
        val pts = leg.candidates.mapNotNull { routes[it.lineId] }.flatten()
        if (pts.isEmpty()) return false
        return pts.any { Geo.distanceM(loc.latitude, loc.longitude, it.first, it.second) <= 400 }
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
        if (riding) return rideTick(leg, loc, now)

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
        // 进了地铁站 GPS 就没了：最后一次卫星定位在站口 500 米内、之后 90 秒没信号 → 已进站
        val g = lastGpsFix
        if (leg.isMetroOnly() && g != null && now - g.time > 90_000 &&
            leg.candidates.any { Geo.distanceM(g.latitude, g.longitude, it.lat, it.lng) <= 500 }) {
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
                        Candidate.Type.METRO -> {
                            // 车来了没有地铁列车实时位置：按本站该方向首末班 + 高峰/平峰间隔推算
                            val dirs = metroDirs.getOrPut("${c.stopName}|${c.name}") {
                                runCatching { ChelaileApi.subwayDirs(c.lat, c.lng, c.stopName, c.cityId) }.getOrNull()
                                    ?.filter { ChelaileApi.sameLine(c.name, it) } ?: return@async Decision.Option(c, walk, emptyList())
                            }.filter { c.metroDir.isEmpty() || it.dest == c.metroDir }
                            val weekday = LocalDate.now().dayOfWeek.value <= 5
                            val arr = dirs.flatMap { d ->
                                Decision.metroSchedule(secOfDay, d.firstMin, d.lastMin, weekday, c.peakHw(), c.offPeakHw())
                                    .map { Decision.Arrival(it, -1, if (dirs.size > 1) "往${d.dest}" else "", estimated = true) }
                            }
                            Decision.Option(c, walk, arr,
                                if (dirs.isNotEmpty() && arr.isEmpty()) Decision.END_OF_SERVICE else "")
                        }
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
            LiveNotifier.alert(this, "${a.headline} · ${a.option?.candidate?.label() ?: trip.name}", a.detail,
                metro = a.option?.candidate?.isMetro() == true)
        }
        // 到换乘点：离下一段上车点 500 米内，或按乘车时间快到了，弹一次
        if (legIdx > 0 && boardedAt > 0 && !transferAlerted &&
            ((nearest != null && nearest <= 500) || (connecting && now >= expectArrive - 90_000))) {
            transferAlerted = true
            val c = a.option?.candidate
            LiveNotifier.alert(this, "快到换乘点 · 换${c?.label() ?: leg.title()}", a.detail,
                metro = c?.isMetro() == true, id = 1004)
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

    /** 最后一段车上：快到下车站时提醒一次，到了就收起 */
    private fun rideTick(leg: Leg, loc: Location?, now: Long): Long {
        val dest = alight ?: run { finish(); return 60_000L }
        val metro = leg.isMetroOnly()
        val name = leg.candidates.firstOrNull { it.alightName.isNotEmpty() }?.alightName ?: "下车站"
        val d = loc?.let { Geo.distanceM(it.latitude, it.longitude, dest.first, dest.second) }
        val eta = boardedAt + leg.rideMin * 60_000L
        val near = d != null && d <= 700
        val timeUp = now >= eta - 90_000 && (d == null || d < 2_500)
        if (arriveAlertedAt == 0L && (near || timeUp)) {
            arriveAlertedAt = now
            LiveNotifier.alert(this, "快到 $name 了", "准备下车", metro = metro, id = 1005)
        }
        if (arriveAlertedAt > 0 && ((d != null && d <= 150) || now - arriveAlertedAt > 5 * 60_000L)) {
            finish(); return 60_000L
        }
        val total = (leg.rideMin * 60_000L).coerceAtLeast(60_000L)
        val p = ((now - boardedAt) * 100 / total).toInt().coerceIn(0, 100)
        val left = ((eta - now) / 60_000L).toInt()
        val short = when {
            arriveAlertedAt > 0 -> "下车"
            d != null && d < 1_500 -> "${d.toInt()}米"
            left > 0 -> "约${left}分"
            else -> "快到了"
        }
        val text = "前往 $name" + (d?.let { " · 还有约 ${"%.1f".format(it / 1000)} 公里" } ?: "")
        val v = LiveNotifier.Visual("${leg.title()} · 到 $name", text, short,
            if (trip.legs.size > 1) "${trip.name} · 最后一段" else trip.name,
            progress = p, metro = metro, trackerRes = if (metro) R.drawable.ic_metro else R.drawable.ic_bus)
        LiveNotifier.update(this, LiveNotifier.build(this, v))
        LiveState.snapshot.value = LiveSnapshot(trip.id, trip.name, legIdx, trip.legs.size, leg.title(), short, text, null, p, null)
        setPower(if (d != null && d < 1_500) Power.HIGH else Power.LOW)
        return 20_000L
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
        val metro = c?.isMetro() == true || leg.isMetroOnly()
        val icon = if (metro) R.drawable.ic_metro else R.drawable.ic_bus

        if (connecting) {
            val prev = trip.legs[legIdx - 1]
            val total = prev.rideMin * 60_000L
            val p = if (total > 0) (100 - (expectArrive - now) * 100 / total).toInt() else 50
            return LiveNotifier.Visual(title, a.detail, a.headline, sub,
                countdownTo = arr?.let { now + it.etaSec * 1000L }, progress = p, stops = 0,
                urgent = a.mode == Decision.Mode.CONNECT_MISS, metro = metro,
                trackerRes = if (prev.isMetroOnly()) R.drawable.ic_metro else R.drawable.ic_bus)
        }
        if (a.mode == Decision.Mode.METRO) {
            return LiveNotifier.Visual(title, a.detail, a.headline, sub, progress = null,
                trackerRes = R.drawable.ic_walk, metro = true)
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
            urgent = a.mode == Decision.Mode.HURRY, metro = metro,
            trackerRes = icon)
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
        if (loc.provider == LocationManager.GPS_PROVIDER) lastGpsFix = loc
        if (!::trip.isInitialized || riding) return

        // 中途才打开：还没到过上车点、但速度像坐车且贴着这条线走 → 说明已经在车上了
        if (loc.hasSpeed()) {
            speeds.addLast(loc.speed); if (speeds.size > 5) speeds.removeFirst()
            routeHits.addLast(onRoute(loc, trip.legs[legIdx])); if (routeHits.size > 5) routeHits.removeFirst()
            val onLine = routeHits.size >= 3 && routeHits.toList().takeLast(3).all { it }
            if (nearSince == 0L && boardedAt == 0L && !trip.legs[legIdx].isMetroOnly() &&
                Motion.classify(speeds.toList(), onLine) == Motion.State.RIDE) {
                speeds.clear(); routeHits.clear()
                onBoarded(); return
            }
        }

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
