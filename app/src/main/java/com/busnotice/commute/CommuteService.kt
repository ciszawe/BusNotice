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
    val riding: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    // 仅供界面显示：车上已过几站 / 上车到下车共几站（没有站点数据时为 null）
    val stopsPassed: Int? = null,
    val stopsTotal: Int? = null,
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

        fun start(ctx: Context, tripId: String, manual: Boolean, fresh: Boolean = false) {
            val i = Intent(ctx, CommuteService::class.java)
                .putExtra(EXTRA_TRIP_ID, tripId)
                .putExtra(EXTRA_MANUAL, manual)
                .putExtra("fresh", fresh)
            ctx.startForegroundService(i)
        }

        /** 有没结束的行程但服务不在（被系统杀了）：重新拉起，接着原进度跑 */
        fun resumeIfNeeded(ctx: Context) {
            if (instance != null) return
            val p = ctx.getSharedPreferences("run_state", Context.MODE_PRIVATE)
            val id = p.getString("trip", null) ?: return
            if (p.getLong("deadline", 0) <= System.currentTimeMillis()) return
            runCatching { start(ctx, id, p.getBoolean("manual", false)) }
                .onSuccess { Diag.mark(ctx, "胶囊不见了，已重新拉起") }
        }

        /** 用户主动结束：清掉进度，不再恢复 */
        fun stop(ctx: Context) {
            val svc = instance
            if (svc != null) { svc.userFinish(); return }
            ctx.getSharedPreferences("run_state", Context.MODE_PRIVATE).edit().clear().apply()
            ctx.stopService(Intent(ctx, CommuteService::class.java))
        }
    }

    private enum class Power { OFF, LOW, HIGH }

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
    // 最后一段坐公交：这条线上车站..下车站的站点（内存缓存，恢复后重新取）和沿线进度
    @Volatile private var busRoute: List<ChelaileApi.StopPoint>? = null
    @Volatile private var busRouteLine = ""
    private var busRouteTries = 0
    private var busRouteTriedAt = 0L
    private var busSeg = -1            // 当前投到的段
    private var busMaxSeg = -1         // 到过的最远段：不许往回退超过 1 站
    private var busSnap: BusRide.Snap? = null
    private var busSnapAt = 0L
    private var busEtaAt = 0L          // 按沿线距离估的到站时刻

    private val listener = LocationListener { loc -> onLocation(loc) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent 为空 = 被系统杀掉后自动拉起：接着跑中断前那趟
        val id = intent?.getStringExtra(EXTRA_TRIP_ID) ?: savedRunTrip()
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
        val manual = intent?.getBooleanExtra(EXTRA_MANUAL, false) ?: false

        instance = this
        LiveNotifier.hidden = false
        if (intent == null) Diag.mark(this, "服务被系统杀掉后自动重启")
        else if (!manual) Diag.mark(this, "已自动启动")
        trip = t
        restored = false
        startedAt = System.currentTimeMillis()
        runCatching { TraceLog.start(this, t.id, t.name) }
        TraceLog.event("run", "manual" to manual, "restart" to (intent == null))
        stationAt = 0L; metroRideSec.clear(); metroRoutes.clear(); alightAt = 0L; fenceFixes.clear(); stillSince = 0L; lastFastAt = 0L; rideLearned = false; stopAlerted = ""
        stationByTap = false; arriveAnchorAt = 0L
        legIdx = 0; boardedAt = 0L; nearSince = 0L; fastFixes = 0; alertedKey = null; planDone = false; planAlerted = ""
        maxStops.clear(); maxEta.clear()
        manualRun = manual; pendingBoardAt = 0L; pendingBoardLoc = null; boardCommitted = false; riding = false; transferAlerted = false; arriveAlertedAt = 0L
        speeds.clear(); routeHits.clear(); lastGpsFix = null
        resetBusRide(); busRoute = null; busRouteLine = ""
        power = null; highSince = 0L; coolUntil = 0L
        setPower(Power.HIGH) // 预热：先拿到一个准的位置
        scope.launch(Dispatchers.IO) { prefetchMetroStops(t); prefetch(t) }
        loop?.cancel()
        // 围栏拉起的是一趟全新的行程：不接中断前的进度
        val fresh = intent?.getBooleanExtra("fresh", false) == true
        if (fresh) clearRun()
        val resumedDl = if (fresh) null else restoreRun(t)
        loop = scope.launch { runLoop(resumedDl ?: if (fresh) freshDeadline(t) else deadline(t, manual)) }
        return START_STICKY
    }

    private fun freshDeadline(t: Trip): Long =
        System.currentTimeMillis() + (t.legs.sumOf { it.rideMin + it.transferWalkMin } + 50) * 60_000L

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

    /** 这段大概坐多久：实测攒够 3 次用实测中位数，否则用手填的 */
    private fun rideMs(i: Int): Long {
        Learn.rideMin(this, trip.id, i)?.let { return it * 60_000L }
        metroRideSec[i]?.let { return it * 1000L }
        return trip.legs[i].rideMin * 60_000L
    }

    /** 地铁段按站数估的乘车秒数（每站约 2 分 20 秒，含停站） */
    private val metroRideSec = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val SEC_PER_STOP = 140

    /** 每段地铁途经的站（站名 + 坐标），拿来显示「下一站」 */
    private val metroRoutes = java.util.concurrent.ConcurrentHashMap<Int, ChelaileApi.MetroRoute>()

    private fun prefetchMetroStops(t: Trip) {
        t.legs.forEachIndexed { i, leg ->
            if (!leg.isMetroOnly()) return@forEachIndexed
            val c = leg.candidates.firstOrNull { it.isMetro() } ?: return@forEachIndexed
            MetroRoutes.target(t, i)?.let { (name, la, ln) ->
                MetroRoutes.get(this, c, name, la, ln)?.let { r ->
                    metroRoutes[i] = r
                    metroRideSec[i] = r.durSec
                    Log.i(TAG, "第 ${i + 1} 段地铁 ${r.hops} 站，约 ${r.durSec / 60} 分钟")
                }
            }
            if (i >= t.legs.size - 1) return@forEachIndexed
            val to = t.legs[i + 1].candidates.firstOrNull() ?: return@forEachIndexed
            runCatching {
                val dirs = ChelaileApi.subwayDirs(c.lat, c.lng, c.stopName, c.cityId).filter { ChelaileApi.sameLine(c.name, it) }
                metroDirs.putIfAbsent("${c.stopName}|${c.name}", dirs)
                val use = dirs.filter { c.metroDir.isEmpty() || it.dest == c.metroDir }
                ChelaileApi.metroStops(use, to.lat, to.lng, c.cityId)
            }.getOrNull()?.let { n ->
                if (metroRideSec.putIfAbsent(i, n * SEC_PER_STOP + 60) == null) Log.i(TAG, "第 ${i + 1} 段地铁约 $n 站")
            }
        }
    }

    /** 进站时刻之后（留 1 分钟走到站台），第一班车几点到；拿不到班次返回 null */
    private fun nextTrainAfter(leg: Leg, atMillis: Long): Long? {
        val c = leg.candidates.firstOrNull { it.isMetro() } ?: return null
        val dirs = metroDirs["${c.stopName}|${c.name}"]?.filter { c.metroDir.isEmpty() || it.dest == c.metroDir } ?: return null
        val at = java.time.Instant.ofEpochMilli(atMillis + 60_000).atZone(ZoneId.systemDefault())
        val sec = at.toLocalTime().toSecondOfDay()
        val weekday = at.dayOfWeek.value <= 5
        val peak = Decision.headwayAt(sec / 60, weekday, 1, 2) == 1
        val off = if (c.metroDir.isNotEmpty()) Learn.metroOffsetSec(this, Learn.metroKey(c, peak)) else 0
        val eta = dirs.mapNotNull { d ->
            Decision.metroSchedule(sec - off, d.firstMin, d.lastMin, weekday, c.peakHw(), c.offPeakHw(), 1).firstOrNull()
        }.minOrNull() ?: return null
        return atMillis + 60_000 + eta * 1000L
    }

    private var startedAt = 0L
    private var stationAt = 0L     // 推断进站的时刻
    private var alightAt = 0L      // 推断下了上一段地铁的时刻
    private val fenceFixes = ArrayDeque<android.location.Location>()
    private var pendingBoardAt = 0L
    private var pendingBoardLoc: Location? = null
    private var boardCommitted = false

    /**
     * 真坐车 vs 测试：上车后离上车点 1.5 公里以上、或已经到了下一段（换乘/下地铁），
     * 且距上车已过 5 分钟，才算一次真实乘车写进习惯。原地反复点测试永远够不上。
     */
    private fun commitBoard(now: Long) {
        if (pendingBoardAt == 0L || boardCommitted || manualRun) return
        if (now - pendingBoardAt < 5 * 60_000L) return
        val from = pendingBoardLoc
        val moved = from != null && best?.let { b ->
            b.time > pendingBoardAt && b.accuracy < 300 &&
                Geo.distanceM(from.latitude, from.longitude, b.latitude, b.longitude) > 1_500
        } == true
        val progressed = legIdx > 0 && (alightAt > 0 || riding || legIdx > 1)
        if (!moved && !progressed) return
        boardCommitted = true
        Learn.record(this, trip.id, pendingBoardAt)
        Learn.autoAdjust(this, trip)?.let { Scheduler.schedule(this, it) }
        Log.i(TAG, "记下一次真实乘车：${if (moved) "位移" else "到了下一段"}")
    }
    private var stillSince = 0L
    private var lastFastAt = 0L

    /** 这段地铁的下车站坐标：途经站数据 > 候选里填的下车站 > 下一段上车点 */
    private fun alightStation(i: Int): Pair<Double, Double>? {
        metroRoutes[i]?.stations?.lastOrNull()?.let { return it.lat to it.lng }
        MetroRoutes.target(trip, i)?.let { return it.second to it.third }
        return null
    }
    private var rideLearned = false
    private var stopAlerted = ""   // 已在站牌边提醒过的那班车
    private var steps = 0
    private var stepOn = false
    private val stepListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) { steps++ }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    /** 计步（低功耗协处理器）：只在快到换乘站前后几分钟开，用来判断下车开始走路 */
    private fun stepSensor(on: Boolean) {
        if (on == stepOn) return
        val sm = getSystemService(android.hardware.SensorManager::class.java) ?: return
        if (on) {
            if (checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_DETECTOR) ?: return
            steps = 0
            stepOn = sm.registerListener(stepListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            sm.unregisterListener(stepListener); stepOn = false
        }
    }
    private var lastCand: Candidate? = null    // 最近一次建议坐的线
    private var ridingCand: Candidate? = null  // 最后一段实际坐的线

    /** 地铁段手动点「已上车」= 真实列车时刻，拿来修正推算 */
    private fun learnMetro(leg: Leg) {
        val c = leg.candidates.firstOrNull { it.isMetro() && it.metroDir.isNotEmpty() } ?: return
        val d = metroDirs["${c.stopName}|${c.name}"]?.firstOrNull { it.dest == c.metroDir } ?: return
        val nowSec = LocalTime.now().toSecondOfDay()
        val weekday = LocalDate.now().dayOfWeek.value <= 5
        val r = Decision.metroResidual(nowSec - 30, d.firstMin, d.lastMin, weekday, c.peakHw(), c.offPeakHw()) ?: return
        val peak = Decision.headwayAt(nowSec / 60, weekday, 1, 2) == 1
        Learn.recordMetro(this, Learn.metroKey(c, peak), r)
    }

    /** 「已上车」按钮或速度判断：进入下一段；最后一段则进入「去下车站」，再按一次结束 */
    fun onBoarded(manual: Boolean = true, at: Long = System.currentTimeMillis()) {
        if (!::trip.isInitialized) { finish(); return }
        val now = at
        TraceLog.event("boarded", "leg" to legIdx, "manual" to manual, "at" to at, "riding" to riding)
        stationAt = 0L
        if (manual && !riding && trip.legs[legIdx].isMetroOnly()) runCatching { learnMetro(trip.legs[legIdx]) }
        // 只学自动启动的行程：手动点开测试的不算习惯；先挂起，确认真的坐了一段才记（commitBoard）
        if (legIdx == 0 && boardedAt == 0L && !manualRun && !boardCommitted) {
            pendingBoardAt = now; pendingBoardLoc = best?.let { Location(it) }
        }
        if (legIdx >= trip.legs.size - 1) {
            if (riding) return            // 已经在车上：重复点「已上车」不做任何事
            // 下车站坐标还在后台取（刚启动/刚恢复）时别结束；整趟根本没填下车站才结束
            if (alight == null && trip.legs[legIdx].candidates.none { it.alightName.isNotEmpty() || !it.alightLat.isNaN() }) { finish(); return }
            riding = true; boardedAt = now; arriveAlertedAt = 0L; resetBusRide()
            val l = trip.legs[legIdx]
            ridingCand = l.candidates.firstOrNull { it.isMetro() }.takeIf { l.isMetroOnly() } ?: lastCand ?: l.candidates.firstOrNull()
            speeds.clear(); routeHits.clear()
            loop?.cancel()
            val dl = maxOf(at, System.currentTimeMillis()) + rideMs(trip.legs.size - 1) + 20 * 60_000L
            deadlineAt = dl   // 先同步更新，紧接着的 kick()/saveRun() 才不会用旧截止时间
            loop = scope.launch { runLoop(dl) }
            saveRun(dl)
            return
        }
        boardedAt = now
        legIdx++
        nearSince = 0L; fastFixes = 0; alertedKey = null; transferAlerted = false
        alightAt = 0L; rideLearned = false; stopAlerted = ""; stepSensor(false)
        stationByTap = false; arriveAnchorAt = 0L
        fenceFixes.clear(); stillSince = 0L; lastFastAt = 0L
        maxStops.clear(); maxEta.clear(); speeds.clear(); routeHits.clear()
        loop?.cancel()
        val dl = deadline(trip, false).coerceAtLeast(now + 20 * 60_000L)
        loop = scope.launch { runLoop(dl) }
        saveRun(dl)
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

    private var deadlineAt = 0L

    private suspend fun runLoop(deadline: Long) {
        deadlineAt = deadline
        while (scope.isActive && System.currentTimeMillis() < deadlineAt) {
            val wait = try { tick() } catch (e: Exception) {
                Log.w(TAG, "刷新失败", e); 20_000L
            }
            delay(wait)
        }
        finish()
    }

    /** 刷新一次，返回下次刷新的间隔 */
    private suspend fun tick(): Long {
        commitBoard(System.currentTimeMillis())
        val leg = trip.legs[legIdx]
        val prev = trip.legs.getOrNull(legIdx - 1)
        val now = System.currentTimeMillis()
        val loc = best?.takeIf { it.accuracy < 200 && now - it.time < 120_000 }
        // 地铁里网络定位误差大（几百米到一公里），坐地铁时放宽到 800 米也拿来用
        if (riding) return rideTick(leg, loc ?: best?.takeIf { leg.isMetroOnly() && it.accuracy < 800 && now - it.time < 90_000 }, now)

        // 按位置纠正进度：人已经在后面某一段的上车站附近、离当前这段的站很远，说明前面几段已经坐完了（没识别到上车）
        jumpByLocation(now)?.let { return it }

        val dists = leg.candidates.map { c -> loc?.let { Geo.distanceM(it.latitude, it.longitude, c.lat, c.lng) } }
        val nearest = dists.filterNotNull().minOrNull()
        if (nearest != null && nearest <= NEAR_STOP_M && nearSince == 0L) {
            nearSince = now
            // 换乘到了：记下上一段实际坐了多久，下次推算换乘更准
            if (prev != null && boardedAt > 0 && !rideLearned) {
                rideLearned = true
                Learn.recordRide(this, trip.id, legIdx - 1, ((now - boardedAt) / 60_000L).toInt() - prev.transferWalkMin)
            }
        }

        // 换乘中：还在上一段车上，按「剩余乘车 + 换乘步行」估算到下一站的时间
        val expectArrive = if (boardedAt > 0 && prev != null) (anchoredArrive(legIdx - 1) ?: (boardedAt + rideMs(legIdx - 1))) else 0L
        val fromMetro = prev?.isMetroOnly() == true
        // 判断下了地铁：地理围栏（换乘站附近）+ 移动速度 + 计步，三者配合
        if (fromMetro && expectArrive > 0 && alightAt == 0L) {
            val window = now >= expectArrive - 3 * 60_000L
            stepSensor(window)
            val st = alightStation(legIdx - 1)
            // 任何来源的新位置（站里多是网络定位，误差放宽到 500 米）
            val fix = best?.takeIf { now - it.time < 60_000 && it.time > boardedAt && it.accuracy < 500 }
            if (fix != null && (fenceFixes.isEmpty() || fenceFixes.last().time != fix.time)) {
                fenceFixes.addLast(android.location.Location(fix)); while (fenceFixes.size > 6) fenceFixes.removeFirst()
            }
            // 两次定位之间 ≥ 6 米/秒 = 还在车上：清零步数（车厢里走动不算下车），也清掉静止计时
            val pair = fenceFixes.toList().takeLast(2)
            val speed = if (pair.size == 2 && pair[1].time > pair[0].time)
                Geo.distanceM(pair[0].latitude, pair[0].longitude, pair[1].latitude, pair[1].longitude) /
                    ((pair[1].time - pair[0].time) / 1000.0) else null
            if (speed != null && speed >= 6.0) { steps = 0; stillSince = 0L; lastFastAt = now }
            val dist = if (fix != null && st != null) Geo.distanceM(fix.latitude, fix.longitude, st.first, st.second) else null
            val inFence = dist != null && dist <= 350 + fix!!.accuracy.coerceAtMost(200f)
            if (inFence && (speed == null || speed < 2.0)) { if (stillSince == 0L) stillSince = now } else if (!inFence) stillSince = 0L
            val recentlyFast = now - lastFastAt < 60_000
            val noLocFor = fenceFixes.lastOrNull()?.let { now - it.time } ?: Long.MAX_VALUE
            // 加速度：列车停稳（静止）之后持续走路 ≥ 20 秒 = 下车往外走；只当佐证，要配合时间窗和位置
            val wins = if (window) TraceLog.recentWindows() else emptyList()
            val walkStart = AccMotion.walkingSustainedSince(wins, now)?.takeIf { it > boardedAt }
            val motionOk = window && walkStart != null && AccMotion.hadStillBefore(wins, walkStart) && !recentlyFast &&
                (inFence || dist == null || noLocFor > 2 * 60_000L)
            val by = when {
                // 出站：卫星定位恢复（地下收不到卫星）
                lastGpsFix?.let { now - it.time < 60_000 && it.time > boardedAt } == true &&
                    now >= expectArrive - 2 * 60_000L && (dist == null || dist < 1_500) -> "gps"
                // 停稳后开始持续走路（加速度），且在换乘站附近或站里没定位
                motionOk -> "motion"
                // 在换乘站围栏内 + 走了 25 步，且最近一分钟没有车速移动
                window && inFence && stepOn && steps >= 25 && !recentlyFast -> "fence+steps"
                // 在换乘站围栏内静止超过 90 秒（站台上等、在站里坐着等公交）
                window && inFence && stillSince > 0 && now - stillSince >= 90_000 -> "fence+still"
                // 完全没有位置时才单靠步数
                window && noLocFor > 3 * 60_000L && stepOn && steps >= 40 -> "steps"
                // 兜底：过了预计时间 3 分钟；但定位显示离换乘站还远且在动，说明晚点了，最多再等 10 分钟
                now >= expectArrive + 3 * 60_000L && !(dist != null && dist > 1_500 && recentlyFast &&
                    now < expectArrive + 10 * 60_000L) -> "time"
                else -> null
            }
            if (by != null) {
                // 按加速度判断时，下车时刻是开始走路的时刻（换乘步行从那时算）
                val at = if (by == "motion") (walkStart ?: now) else now
                alightAt = at; stepSensor(false); saveRun(deadlineAt)
                Log.i(TAG, "判断已下地铁：$by")
                TraceLog.event("alight_detect", "leg" to legIdx - 1, "by" to by, "at" to at)
                if (by != "time" && !rideLearned) {
                    rideLearned = true
                    Learn.recordRide(this, trip.id, legIdx - 1, ((at - boardedAt) / 60_000L).toInt())
                }
            }
        }
        val connecting = expectArrive > 0 && nearSince == 0L && (nearest == null || nearest > 300) &&
            (if (fromMetro) alightAt == 0L else now < expectArrive + 5 * 60_000L)
        val connectSec = if (connecting) (((expectArrive - now) / 1000).toInt().coerceAtLeast(0) + leg.transferWalkMin * 60) else 0

        // 推断进站：在站口附近待了两分钟，或最后一次卫星定位在站口 500 米内、之后 90 秒没信号。
        // 进站 ≠ 上车：按进站后第一班赶得上的车算上车时刻，那班车开走了才切到下一段。
        if (leg.isMetroOnly() && stationAt == 0L) {
            val g = lastGpsFix
            stationAt = when {
                nearSince > 0 && now - nearSince > 120_000 -> nearSince
                g != null && g.time >= startedAt - 30_000 && now - g.time > 90_000 &&
                    leg.candidates.any { Geo.distanceM(g.latitude, g.longitude, it.lat, it.lng) <= 500 } -> g.time
                else -> 0L
            }
        }
        if (leg.isMetroOnly() && stationAt > 0) {
            val train = nextTrainAfter(leg, stationAt) ?: (stationAt + 4 * 60_000L)
            if (now >= train + Decision.DWELL_SEC * 1000L) { onBoarded(manual = false, at = train); return 1_000L }
        }

        val secOfDay = LocalTime.now().toSecondOfDay()
        val options = withContext(Dispatchers.IO) {
            leg.candidates.mapIndexed { i, c ->
                async {
                    val walk = if (connecting) connectSec else dists[i]?.let { Decision.walkSeconds(it) }
                        // 站里没定位：按换乘步行时间减去下车后已走的时间
                        ?: if (alightAt > 0) (leg.transferWalkMin * 60 - ((now - alightAt) / 1000).toInt()).coerceAtLeast(0) else 0
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
                            val peakNow = Decision.headwayAt(secOfDay / 60, weekday, 1, 2) == 1
                            val off = if (c.metroDir.isNotEmpty()) Learn.metroOffsetSec(this@CommuteService, Learn.metroKey(c, peakNow)) else 0
                            val arr = dirs.flatMap { d ->
                                Decision.metroSchedule(secOfDay - off, d.firstMin, d.lastMin, weekday, c.peakHw(), c.offPeakHw(), 3, Decision.DWELL_SEC)
                                    .map { Decision.Arrival(it, -1, if (dirs.size > 1) "往${d.dest}" else "", estimated = true) }
                            }
                            Decision.Option(c, walk, arr,
                                if (dirs.isNotEmpty() && arr.isEmpty()) Decision.END_OF_SERVICE else "",
                                located = connecting || dists[i] != null)
                        }
                    }
                }
            }.awaitAll()
        }
        val a = Decision.decide(options, connecting)
        a.option?.candidate?.let { lastCand = it }
        // 先坐地铁再换公交、还没出发：倒推「最晚几点走，到换乘站刚好赶上公交」
        val plan = if (!planDone && legIdx == 0 && boardedAt == 0L && nearSince == 0L && leg.isMetroOnly())
            runCatching { planTransfer(leg, options, secOfDay, loc) }.getOrNull() else null
        if (plan != null) return planTick(plan, leg, a, now)
        val v = visual(a, leg, connecting, expectArrive, now)
        LiveNotifier.update(this, LiveNotifier.build(this, v))
        LiveState.snapshot.value = LiveSnapshot(
            trip.id, trip.name, legIdx, trip.legs.size, leg.title(), v.short, v.text, a.mode, v.progress, v.countdownTo,
        )

        // 该走了 / 换乘紧张：横幅 + 震动，每班车只提醒一次
        val key = "${a.mode}|${a.option?.candidate?.label()}|${a.arrival?.busId}"
        // 还在地铁上（离到换乘站超过 5 分钟）：不弹公交的提醒
        val onMetro = connecting && prev?.isMetroOnly() == true && now < expectArrive - 5 * 60_000L
        if (!onMetro && (a.mode == Decision.Mode.LEAVE_NOW || a.mode == Decision.Mode.HURRY || a.mode == Decision.Mode.CONNECT_MISS) &&
            alertedKey != key) {
            alertedKey = key
            LiveNotifier.alert(this, "${a.headline} · ${a.option?.candidate?.label() ?: trip.name}", a.detail,
                metro = a.option?.candidate?.isMetro() == true)
        }
        // 到换乘点：离下一段上车点 500 米内，或按乘车时间快到了，弹一次
        if (legIdx > 0 && boardedAt > 0 && !transferAlerted &&
            ((nearest != null && nearest <= 500) || (connecting && now >= expectArrive - 180_000))) {
            transferAlerted = true
            val c = a.option?.candidate
            val at = prev?.candidates?.firstOrNull { it.alightName.isNotEmpty() }?.alightName ?: "换乘站"
            LiveNotifier.alert(this, "快到 $at · ${c?.label() ?: leg.title()} ${a.headline}", a.detail,
                metro = c?.isMetro() == true, id = 1004)
        }
        // 已在站牌边：车 2 分钟内到，再提醒一次（每班车一次）
        val arrSec = a.arrival?.etaSec ?: -1
        val busKey = "${a.option?.candidate?.label()}|${a.arrival?.busId}"
        if (nearSince > 0 && arrSec in 0..120 && stopAlerted != busKey && a.option?.candidate?.isMetro() != true) {
            stopAlerted = busKey
            LiveNotifier.alert(this, "${a.option?.candidate?.label()} 快到了", a.detail, id = 1006)
        }
        record(a, nearest)

        // 省电：快出发、在站台附近才开高频 GPS
        val hot = when (a.mode) {
            Decision.Mode.LEAVE_NOW, Decision.Mode.HURRY, Decision.Mode.AT_STOP -> true
            Decision.Mode.LEAVE_LATER -> a.leaveInSec < 300
            Decision.Mode.METRO -> nearest != null && nearest < 200
            else -> nearest != null && nearest < 300
        } || loc == null
        when {
            // 坐地铁时地下搜不到卫星：关掉定位只按时间倒计时，快到换乘站前 5 分钟再开
            // 地铁里收不到卫星，但基站/Wi-Fi 网络定位还能用：保持低功耗网络定位
            onMetro -> setPower(Power.LOW)
            connecting && prev?.isMetroOnly() == true -> setPower(Power.HIGH)
            else -> setPower(if (hot) Power.HIGH else Power.LOW)
        }
        if (onMetro) return 60_000L

        val soonest = a.arrival?.etaSec ?: Int.MAX_VALUE
        return when {
            a.mode == Decision.Mode.METRO -> 30_000L
            hot || soonest < 600 -> 20_000L
            else -> 40_000L
        }
    }

    /**
     * 按位置纠正进度，两种情况跳到后面那一段：
     * 1. 新鲜定位在后面某段上车站 250 米内，且离当前段的站都在 600 米外；
     * 2. 地下没信号：最后一次定位在后面某段地铁站 500 米内，之后 90 秒以上没有新定位 → 进站了。
     */
    private fun jumpByLocation(now: Long): Long? {
        // 只认这趟行程开始之后拿到的定位：系统缓存的旧位置（比如上次进地铁时）会让刚启动就误跳段
        val last = best?.takeIf { it.accuracy < 300 && it.time >= startedAt - 30_000 } ?: return null
        fun near(l: Leg) = l.candidates.filter { !it.lat.isNaN() }
            .minOfOrNull { Geo.distanceM(last.latitude, last.longitude, it.lat, it.lng) } ?: Double.MAX_VALUE
        val age = now - last.time
        val fresh = age < 120_000 && last.accuracy < 150
        val later = legIdx + 1 until trip.legs.size
        val k = when {
            fresh && near(trip.legs[legIdx]) >= 600 -> later.firstOrNull { near(trip.legs[it]) <= 250 }
            !fresh && age > 90_000 && near(trip.legs[legIdx]) >= 300 ->
                later.firstOrNull { trip.legs[it].isMetroOnly() && near(trip.legs[it]) <= 500 }
            else -> null
        } ?: return null
        Diag.mark(this, if (fresh) "定位显示已到第 ${k + 1} 段上车站附近，跳过前面的段" else "在第 ${k + 1} 段地铁站附近没了信号，判断已进站")
        val firstAt = if (legIdx == 0 && boardedAt == 0L) last.time - rideMs(0) else now
        onBoarded(manual = false, at = firstAt)
        while (legIdx < k) onBoarded(manual = false, at = now)
        boardedAt = now - rideMs(k - 1); alightAt = now; rideLearned = true
        if (trip.legs[k].isMetroOnly()) { stationAt = if (fresh) 0L else last.time; if (fresh) nearSince = now }
        return 1_000L
    }

    private var planAlerted = ""
    private var planDone = false   // 提醒过「该走了」就不再倒推，改回正常的地铁进站显示

    /** 第一段地铁 + 第二段有公交时，算出发方案；条件不满足返回 null（照常显示地铁） */
    private suspend fun planTransfer(leg: Leg, options: List<Decision.Option>, secOfDay: Int, loc: Location?): List<Decision.Plan>? {
        val next = trip.legs.getOrNull(1) ?: return null
        val buses = next.candidates.filter { it.type == Candidate.Type.BUS }
        if (buses.isEmpty()) return null
        val c = leg.candidates.first()
        val dirs = metroDirs["${c.stopName}|${c.name}"]?.filter { c.metroDir.isEmpty() || it.dest == c.metroDir } ?: return null
        if (dirs.size != 1) return null // 方向不确定就不推，免得算错
        val d = dirs.first()
        val weekday = LocalDate.now().dayOfWeek.value <= 5
        val peakNow = Decision.headwayAt(secOfDay / 60, weekday, 1, 2) == 1
        val off = Learn.metroOffsetSec(this, Learn.metroKey(c, peakNow))
        val trains = Decision.metroSchedule(secOfDay - off, d.firstMin, d.lastMin, weekday, c.peakHw(), c.offPeakHw(), 30)
        val busArr = withContext(Dispatchers.IO) {
            buses.map { b ->
                async {
                    val rt = runCatching { ChelaileApi.lineRealtime(b, b.lat, b.lng) }.getOrNull()
                    Decision.extendBuses(b.label(), rt?.buses?.map { it.travelSec } ?: emptyList(), b.headwayMin * 60)
                }
            }.awaitAll().flatten()
        }
        if (busArr.isEmpty()) return null
        val walk = options.minOfOrNull { it.walkSec } ?: 0
        val plans = Decision.planDeparture(walk, trains, (rideMs(0) / 1000).toInt(), next.transferWalkMin * 60, busArr)
        return plans.ifEmpty { null }
    }

    private fun clock(nowMs: Long, sec: Int): String =
        java.time.Instant.ofEpochMilli(nowMs + sec * 1000L).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            .let { "%02d:%02d".format(it.hour, it.minute) }

    /** 显示「还能待多久」：倒计时到出发时间，10 分钟前和该走时各提醒一次 */
    private fun planTick(plans: List<Decision.Plan>, leg: Leg, a: Decision.Advice, now: Long): Long {
        val p = plans.first()
        val c = leg.candidates.first()
        val stay = Decision.mins(p.leaveSec)
        val est = if (p.busEstimated) "约" else ""
        val text = "${clock(now, p.leaveSec)} 出发 → ${c.label()} ${clock(now, p.trainSec)} → " +
            "${p.busLabel} $est${clock(now, p.busSec)}" + (if (Decision.mins(p.waitSec) == 0) "（换乘几乎不用等）" else "（换乘等约 ${Decision.mins(p.waitSec)} 分）") +
            (plans.getOrNull(1)?.let { " · 晚一班：${clock(now, it.leaveSec)} 走，赶 ${it.busLabel}" } ?: "")
        val short = if (p.leaveSec <= 60) "该走了" else "待${stay}分"
        val title = if (p.leaveSec <= 60) "该出发了 · ${c.stopName}" else "还能待 $stay 分钟"
        val v = LiveNotifier.Visual(title, text, short, trip.name,
            countdownTo = now + p.leaveSec * 1000L, metro = true, trackerRes = R.drawable.ic_metro)
        LiveNotifier.update(this, LiveNotifier.build(this, v))
        LiveState.snapshot.value = LiveSnapshot(
            trip.id, trip.name, legIdx, trip.legs.size, leg.title(), short, text, a.mode, null, v.countdownTo,
        )
        val key = "${p.busLabel}@${(now / 1000 + p.trainSec) / 300}"
        if (p.leaveSec in 0..660 && planAlerted != "$key|10" && planAlerted != "$key|0" && p.leaveSec > 120) {
            planAlerted = "$key|10"
            LiveNotifier.alert(this, "${Decision.mins(p.leaveSec)} 分钟后出发", text, metro = true)
        } else if (p.leaveSec <= 120 && planAlerted != "$key|0") {
            planAlerted = "$key|0"; planDone = true
            LiveNotifier.alert(this, "该走了 · 去${c.stopName}", text, metro = true)
        }
        // 在公司多待一会：胶囊别在出发前就到期收起
        val need = now + p.leaveSec * 1000L + trip.legs.sumOf { it.rideMin + it.transferWalkMin + 10 } * 60_000L
        if (need > deadlineAt) deadlineAt = need
        setPower(if (p.leaveSec < 300) Power.HIGH else Power.LOW)
        return when {
            p.leaveSec < 600 -> 20_000L
            p.leaveSec < 1800 -> 45_000L
            else -> 90_000L     // 离出发还早：少查几次，省电
        }
    }

    private fun resetBusRide() {
        busSeg = -1; busMaxSeg = -1; busSnap = null; busSnapAt = 0L; busEtaAt = 0L
        busRouteTries = 0; busRouteTriedAt = 0L
    }

    /** 坐公交：后台取一次这条线的站点，截出上车站..下车站；失败隔一分钟再试，最多 3 次 */
    private fun ensureBusRoute(rc: Candidate?, now: Long) {
        if (rc == null || rc.isMetro() || rc.lineId.isEmpty()) return
        if (busRouteLine == rc.lineId && busRoute != null) return
        if (busRouteLine != rc.lineId) { busRoute = null; busRouteLine = rc.lineId; busRouteTries = 0; busRouteTriedAt = 0L }
        if (busRouteTries >= 3 || now - busRouteTriedAt < 60_000L) return
        busRouteTries++; busRouteTriedAt = now
        val dest = alight
        scope.launch(Dispatchers.IO) {
            val st = runCatching { ChelaileApi.lineDetail(rc.lineId, rc.cityId) }.getOrNull()?.stations ?: return@launch
            val r = BusRide.slice(st, rc.targetOrder, rc.stopName, rc.lat, rc.lng, rc.alightName,
                if (!rc.alightLat.isNaN()) rc.alightLat else dest?.first ?: Double.NaN,
                if (!rc.alightLng.isNaN()) rc.alightLng else dest?.second ?: Double.NaN)
            if (r != null && busRouteLine == rc.lineId) {
                busRoute = r
                TraceLog.event("bus_route", "stops" to r.size - 1)
            }
        }
    }

    /** 最后一段车上：快到下车站时提醒一次，到了就收起 */
    private fun rideTick(leg: Leg, loc: Location?, now: Long): Long {
        val rc = ridingCand
        val metro = rc?.isMetro() ?: leg.isMetroOnly()
        if (!metro) ensureBusRoute(rc, now)
        val br = if (metro) null else busRoute?.takeIf { rc != null && busRouteLine == rc.lineId }
        val dest = alight ?: br?.last()?.let { it.lat to it.lng }
        val name = rc?.alightName?.takeIf { it.isNotEmpty() }
            ?: leg.candidates.firstOrNull { it.alightName.isNotEmpty() }?.alightName ?: "下车站"
        val lineTitle = rc?.label() ?: leg.title()
        val d = if (dest == null) null else loc?.let { Geo.distanceM(it.latitude, it.longitude, dest.first, dest.second) }
        // 新鲜可靠的定位（2 分钟内、200 米精度内）说离下车站还远：人肯定还在车上
        val freshFar = loc != null && now - loc.time < 120_000 && loc.accuracy < 200 && d != null && d > 1_500

        // 公交沿线进度：投到最近一段，不许往回退超过 1 站
        val speed = Learn.busSpeed(this, trip.id, legIdx) ?: BusRide.DEFAULT_M_PER_MIN
        if (br != null && loc != null && loc.time != busSnapAt) {
            BusRide.snap(br, loc.latitude, loc.longitude, busMaxSeg - 1)?.takeIf { it.offM <= 1_500 }?.let { sn ->
                if (sn.seg != busSeg) {
                    TraceLog.event("bus_stop", "idx" to sn.seg, "left" to sn.stopsLeft)
                    busSeg = sn.seg
                    if (sn.seg > busMaxSeg) busMaxSeg = sn.seg
                    saveRun(deadlineAt)
                }
                busSnap = sn; busSnapAt = loc.time
                busEtaAt = now + (sn.remainM / speed * 60_000L).toLong()
            }
        }
        // 太久没更新的投影不用（3 分钟）
        val bs = busSnap?.takeIf { br != null && now - busSnapAt < 180_000 }

        val timeEta = anchoredArrive(legIdx) ?: (boardedAt + rideMs(legIdx))
        // 公交按沿线距离估过到站时间：取两者晚的，免得时间估短了提前结束
        val eta = if (!metro && busEtaAt > 0) maxOf(timeEta, busEtaAt) else timeEta
        val near = d != null && d <= 700 && (loc?.accuracy ?: 9999f) < 400
        // 地铁段还没拿到站数（服务刚恢复、预取没完成）时，手填的乘车时间不可靠，先不按时间提醒下车
        val etaKnown = !metro || metroRideSec[legIdx] != null || Learn.rideMin(this, trip.id, legIdx) != null
        // 按时间提醒「快到了」：有定位且离下车站 1.5 公里以上时不提醒（地铁里网络定位误差大，放宽精度）
        val timeUp = etaKnown && now >= eta - 90_000 && !freshFar &&
            (d == null || d < 1_500 + (if (metro) (loc?.accuracy ?: 0f) else 0f))
        if (arriveAlertedAt == 0L && (near || timeUp)) {
            arriveAlertedAt = now
            LiveNotifier.alert(this, "快到 $name 了", "准备下车", metro = metro, id = 1005)
        }
        // 结束：到了下车站 150 米内；或提醒后过了 5 分钟且超过预计到站时间（地铁 3 分钟、公交 10 分钟）。
        // 新鲜定位显示离下车站 1.5 公里以上时绝不按时间结束（人还在车上，只是车慢）
        val byLoc = arriveAlertedAt > 0 && d != null && d <= 150
        val byTime = arriveAlertedAt > 0 && !freshFar && now - arriveAlertedAt > 5 * 60_000L &&
            now > eta + (if (metro) 3 else 10) * 60_000L
        if (byLoc || byTime) {
            if (byLoc && br != null && !restored && boardedAt > 0) {
                val min = (now - boardedAt) / 60_000.0
                if (min >= 3) Learn.recordBusSpeed(this, trip.id, legIdx, BusRide.lengthM(br) / min)
            }
            TraceLog.event("ride_end", "by" to (if (byLoc) "loc" else "time"))
            finish(); return 60_000L
        }
        // 车比预计慢、人还在车上：截止时间往后顺延，别到点自己收起（最多顺延到乘车时间的 2 倍 + 40 分钟）
        if (freshFar && deadlineAt - now < 10 * 60_000L) {
            val cap = boardedAt + rideMs(legIdx) * 2 + 40 * 60_000L
            val want = minOf(now + 10 * 60_000L, cap)
            if (want > deadlineAt) { deadlineAt = want; saveRun(deadlineAt) }
        }
        val total = rideMs(legIdx).coerceAtLeast(60_000L)
        // 有定位时以距离为准（时间估短了也不会提前显示「快到了」），没定位才按时间
        val span = if (dest == null) null else rc?.let { Geo.distanceM(it.lat, it.lng, dest.first, dest.second) }?.takeIf { it > 300 }
        val hops = (br?.size ?: 0) - 1
        val p = when {
            bs != null && hops > 0 -> (bs.seg * 100 / hops).coerceIn(0, 97)
            d != null && span != null -> ((1 - d / span) * 100).toInt().coerceIn(0, 97)
            else -> ((now - boardedAt) * 100 / total).toInt().coerceIn(0, 100)
        }
        val left = if (d != null && d > 1_500) (d / (if (metro) 550.0 else 350.0)).toInt().coerceAtLeast(1)
            else ((eta - now) / 60_000L).toInt()
        val short = when {
            // 公交有定位时以距离为准；地铁/没定位才按时间
            arriveAlertedAt > 0 && (d != null && d <= 700 || ((metro || d == null) && now >= eta - 60_000)) -> "下车"
            bs != null -> {
                val m = bs.remainM / speed
                when {
                    bs.remainM < 300 || m < 1 -> "即将到站"
                    bs.stopsLeft >= 3 -> "${bs.stopsLeft}站"
                    else -> "约${kotlin.math.ceil(m).toInt()}分"
                }
            }
            arriveAlertedAt > 0 -> "快到了"
            d != null && d < 1_500 -> "${d.toInt()}米"
            left > 0 -> "约${left}分"
            else -> "快到了"
        }
        val mr = if (metro) metroRoutes[legIdx] else null
        val text = when {
            mr != null && arriveAlertedAt == 0L -> {
                val (next, n) = MetroRoutes.progress(mr, now - boardedAt, total)
                "下一站 $next · 还有 $n 站到 $name"
            }
            bs != null && br != null ->
                if (bs.stopsLeft <= 1) "下一站就是 $name" else "下一站 ${br[bs.seg + 1].name} · 还有 ${bs.stopsLeft} 站到 $name"
            else -> "前往 $name" + (d?.let { " · 还有约 ${"%.1f".format(it / 1000)} 公里" } ?: "")
        }
        val v = LiveNotifier.Visual("$lineTitle · 到 $name", text, short,
            if (trip.legs.size > 1) "${trip.name} · 最后一段" else trip.name,
            progress = p, stops = if (bs != null) hops.coerceIn(0, 12) else 0,
            metro = metro, trackerRes = if (metro) R.drawable.ic_metro else R.drawable.ic_bus, riding = true)
        LiveNotifier.update(this, LiveNotifier.build(this, v))
        // 界面用的站点进度：公交按沿线投影，地铁按已坐时间推算（与上面文字同一算法，只读）
        val stopUi: Pair<Int, Int>? = when {
            mr != null && arriveAlertedAt == 0L -> {
                val hopsM = mr.hops.coerceAtLeast(1)
                (hopsM - MetroRoutes.progress(mr, now - boardedAt, total).second) to hopsM
            }
            bs != null && hops > 0 -> bs.seg.coerceIn(0, hops) to hops
            else -> null
        }
        LiveState.snapshot.value = LiveSnapshot(trip.id, trip.name, legIdx, trip.legs.size, lineTitle, short, text, null, p, null, riding = true,
            stopsPassed = stopUi?.first, stopsTotal = stopUi?.second)
        if (metro && boardedAt + total - now > 5 * 60_000L) { setPower(Power.LOW); return 30_000L }
        setPower(if (d != null && d < 1_500) Power.HIGH else Power.LOW)
        // 坐公交离下车站还远：网络定位 + 一分钟刷一次就够
        return if (d != null && d > 3_000) 60_000L else 20_000L
    }

    private fun visual(a: Decision.Advice, leg: Leg, connecting: Boolean, expectArrive: Long, now: Long): LiveNotifier.Visual {
        val c = a.option?.candidate
        val title = when {
            c == null -> trip.name
            c.type == Candidate.Type.METRO -> "${c.label()} · ${c.stopName}"
            else -> "${c.label()} · ${c.stopName}"
        }
        // 换乘中还没确认下车（人还在上一段车上）：段号显示上一段
        val shownLeg = if (connecting && alightAt == 0L && legIdx > 0) legIdx else legIdx + 1
        val sub = if (trip.legs.size > 1) "${trip.name} · 第 $shownLeg/${trip.legs.size} 段" else trip.name
        val arr = a.arrival
        val metro = c?.isMetro() == true || leg.isMetroOnly()
        val icon = if (metro) R.drawable.ic_metro else R.drawable.ic_bus

        if (connecting) {
            val prev = trip.legs[legIdx - 1]
            val total = rideMs(legIdx - 1)
            val p = if (total > 0) (100 - (expectArrive - now) * 100 / total).toInt() else 50
            val r = metroRoutes[legIdx - 1]
            if (r != null && now < expectArrive) {
                val (next, left) = if (arriveAnchorAt > 0) r.stations.last().name to 1
                    else MetroRoutes.progress(r, now - boardedAt, total)
                val line = prev.candidates.firstOrNull { it.isMetro() }?.label() ?: prev.title()
                return LiveNotifier.Visual("$line · 下一站 $next", "还有 $left 站到 ${r.stations.last().name} · ${a.detail}",
                    a.headline, sub, countdownTo = arr?.let { now + it.etaSec * 1000L }, progress = p,
                    stops = r.hops.coerceAtMost(12), metro = true, trackerRes = R.drawable.ic_metro)
            }
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
    /** 以前每次刷新都往 polls.csv 追加一行、从不读取，会无限变大：停写并删掉旧文件 */
    private fun record(@Suppress("UNUSED_PARAMETER") a: Decision.Advice, @Suppress("UNUSED_PARAMETER") dist: Double?) {
        if (!pollsCleaned) { pollsCleaned = true; runCatching { File(filesDir, "polls.csv").delete() } }
    }
    private var pollsCleaned = false

    private var highSince = 0L
    private var coolUntil = 0L

    /**
     * 省电：室内搜不到卫星时 GPS 会一直全速搜星，是发热大户。
     * 高精度开了 90 秒还拿不到 50 米内的定位 → 退回网络定位，冷却 5 分钟再试。
     */
    private fun setPower(want: Power) {
        val now = System.currentTimeMillis()
        var p = want
        if (p == Power.HIGH) {
            if (now < coolUntil) p = Power.LOW
            else if (power == Power.HIGH && highSince > 0 && now - highSince > 90_000 &&
                (best?.let { it.accuracy > 50 || now - it.time > 30_000 } != false)) {
                coolUntil = now + 5 * 60_000L; p = Power.LOW
                Log.i(TAG, "室内搜星无果，降到网络定位")
            }
        }
        if (p == Power.HIGH && power != Power.HIGH) highSince = now
        applyPower(p)
    }

    @SuppressLint("MissingPermission")
    private fun applyPower(p: Power) {
        if (p == power) return
        power = p
        val m = lm ?: getSystemService(LocationManager::class.java).also { lm = it }
        try { m.removeUpdates(listener) } catch (_: Exception) { }
        if (p == Power.OFF) { Log.i(TAG, "定位已暂停"); return }
        val providers = m.allProviders.filter {
            when (p) {
                // GPS + 网络两路就够，不再叠加 fused（它内部也会开 GPS，等于重复）
                Power.HIGH -> it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
                Power.LOW -> it == LocationManager.NETWORK_PROVIDER || it == LocationManager.FUSED_PROVIDER
                Power.OFF -> false
            }
        }.ifEmpty { listOf(LocationManager.GPS_PROVIDER) }
        val interval = if (p == Power.HIGH) 5_000L else 30_000L
        val minDist = if (p == Power.HIGH) 3f else 20f
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

    private var lastFixForSpeed: Location? = null

    /** 网络/融合定位常常不带速度：用前后两次定位自己算 */
    private fun speedOf(loc: Location): Float? {
        if (loc.hasSpeed()) return loc.speed
        val p = lastFixForSpeed
        if (loc.accuracy < 100) lastFixForSpeed = Location(loc)
        if (p == null || loc.accuracy >= 100) return null
        val dt = (loc.time - p.time) / 1000.0
        if (dt < 5 || dt > 120) return null
        return (Geo.distanceM(p.latitude, p.longitude, loc.latitude, loc.longitude) / dt).toFloat()
    }

    private fun onLocation(loc: Location) {
        TraceLog.loc(loc)
        val spd = speedOf(loc)
        val cur = best
        val fresher = cur == null || loc.time - cur.time > 15_000
        if (fresher || loc.accuracy <= cur!!.accuracy) best = loc
        if (loc.provider == LocationManager.GPS_PROVIDER) lastGpsFix = loc
        if (!::trip.isInitialized || riding) return

        // 中途才打开：还没到过上车点、但速度像坐车且贴着这条线走 → 说明已经在车上了
        if (spd != null) {
            speeds.addLast(spd); if (speeds.size > 5) speeds.removeFirst()
            routeHits.addLast(onRoute(loc, trip.legs[legIdx])); if (routeHits.size > 5) routeHits.removeFirst()
            val onLine = routeHits.size >= 3 && routeHits.toList().takeLast(3).all { it }
            if (nearSince == 0L && boardedAt == 0L && !trip.legs[legIdx].isMetroOnly() &&
                Motion.classify(speeds.toList(), onLine) == Motion.State.RIDE) {
                speeds.clear(); routeHits.clear()
                onBoarded(manual = false); return
            }
        }

        // 到过上车点、现在速度很快 → 大概率上车了
        if (nearSince > 0 && spd != null && spd > 6f) {
            fastFixes++
            if (fastFixes >= 3) onBoarded(manual = false)
        } else if (spd != null) {
            fastFixes = 0
        }
    }

    // ---------- 中断恢复：把进行到哪一段存下来，App 被杀/重开后接着跑 ----------
    private fun runPrefs() = getSharedPreferences("run_state", MODE_PRIVATE)

    private fun saveRun(deadline: Long) {
        runPrefs().edit()
            .putString("trip", trip.id).putInt("leg", legIdx).putLong("boardedAt", boardedAt)
            .putBoolean("riding", riding).putLong("alightAt", alightAt).putLong("deadline", deadline).putLong("anchorAt", arriveAnchorAt)
            .putString("ridingCand", ridingCand?.let { "${it.name}|${it.stopName}" } ?: "")
            .putBoolean("manual", manualRun)
            .putInt("busSeg", busMaxSeg)
            .putLong("pendBoard", pendingBoardAt).putBoolean("boardDone", boardCommitted)
            .putString("pendLoc", pendingBoardLoc?.let { "${it.latitude},${it.longitude}" } ?: "").apply()
    }

    private fun clearRun() = runPrefs().edit().clear().apply()

    private fun savedRunTrip(): String? = runPrefs().let { p ->
        p.getString("trip", null)?.takeIf { p.getLong("deadline", 0) > System.currentTimeMillis() }
    }

    /** 有同一趟行程的未结束进度就恢复，返回原来的截止时间；没有返回 null */
    private fun restoreRun(t: Trip): Long? {
        val p = runPrefs()
        val dl = p.getLong("deadline", 0)
        if (p.getString("trip", null) != t.id || dl <= System.currentTimeMillis()) { clearRun(); return null }
        val leg = p.getInt("leg", 0)
        if (leg !in t.legs.indices || p.getLong("boardedAt", 0) <= 0) { clearRun(); return null }
        legIdx = leg
        boardedAt = p.getLong("boardedAt", 0)
        riding = p.getBoolean("riding", false)
        alightAt = p.getLong("alightAt", 0)
        arriveAnchorAt = p.getLong("anchorAt", 0)
        manualRun = p.getBoolean("manual", manualRun)
        pendingBoardAt = p.getLong("pendBoard", 0L); boardCommitted = p.getBoolean("boardDone", false)
        pendingBoardLoc = (p.getString("pendLoc", "") ?: "").split(',').takeIf { it.size == 2 }?.let { (a, b) ->
            val la = a.toDoubleOrNull(); val lo = b.toDoubleOrNull()
            if (la == null || lo == null) null else Location("saved").apply { latitude = la; longitude = lo }
        }
        rideLearned = true // 中断过的这趟不再学乘车时长，免得样本不准
        busMaxSeg = p.getInt("busSeg", -1); busSeg = busMaxSeg   // 站点列表不存，恢复后重新取
        restored = true
        val rc = p.getString("ridingCand", "") ?: ""
        ridingCand = t.legs[leg].candidates.firstOrNull { "${it.name}|${it.stopName}" == rc }
            ?: if (riding) t.legs[leg].candidates.firstOrNull() else null
        Diag.mark(this, "已恢复中断前的行程：第 ${leg + 1} 段${if (riding) "（车上）" else ""}")
        return dl
    }

    private var finishing = false

    /** 打开 App：马上定一次位，按最新位置重算一遍（纠正段落、刷新胶囊） */
    @SuppressLint("MissingPermission")
    fun relocate() {
        val m = lm ?: getSystemService(LocationManager::class.java).also { lm = it } ?: return
        var kicked = false
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).forEach { pr ->
            runCatching {
                if (!m.isProviderEnabled(pr)) return@runCatching
                m.getCurrentLocation(pr, null, mainExecutor) { l ->
                    if (l == null || !::trip.isInitialized) return@getCurrentLocation
                    onLocation(l)
                    // 循环还没真正开始（刚点了运行）时别重启，否则截止时间为 0 会直接结束
                    if (deadlineAt > System.currentTimeMillis() && (!kicked || pr == LocationManager.GPS_PROVIDER)) {
                        kicked = true
                        loop?.cancel(); loop = scope.launch { runLoop(deadlineAt) }
                    }
                }
            }
        }
    }

    private var restored = false
    private var stationByTap = false   // 进站时刻来自刷卡（比定位推断准）
    private var arriveAnchorAt = 0L    // 收到「准备下车」：列车刚离开下车站的前一站

    /** 最后一站间大概要跑多久：按两站距离估，查不到按 2 分钟 */
    private fun lastHopMs(i: Int): Long {
        val st = metroRoutes[i]?.stations
        if (st == null || st.size < 2) return 120_000L
        val a = st[st.size - 2]; val b = st.last()
        val d = Geo.distanceM(a.lat, a.lng, b.lat, b.lng)
        return (60_000L + (d / 16.7 * 1000).toLong()).coerceIn(90_000L, 300_000L)
    }

    /** 有「准备下车」锚点时，预计开门时刻；没有返回 null */
    private fun anchoredArrive(i: Int): Long? = arriveAnchorAt.takeIf { it > 0 }?.let { it + lastHopMs(i) }

    /** 别的 App 的出行通知（NFC 刷卡进/出站、系统到站提醒）：比定位和时间推算都准，直接校准进度 */
    fun onTransitSignal(kind: TransitSignal.Kind, text: String, at: Long) {
        scope.launch(Dispatchers.Main) { runCatching { applyTransit(kind, text, at) }.onFailure { Log.w(TAG, "出行通知处理失败", it) } }
    }

    private fun kick() {
        if (finishing || deadlineAt <= System.currentTimeMillis()) return
        loop?.cancel(); loop = scope.launch { runLoop(deadlineAt) }
    }

    private fun applyTransit(kind: TransitSignal.Kind, text: String, at: Long) {
        if (!::trip.isInitialized) return
        val last = trip.legs.size - 1
        when (kind) {
            TransitSignal.Kind.ENTER -> {
                if (riding) return
                // 已经按刷卡进过站：同一次进站的重复通知不算；之前只是定位推断的，用刷卡时刻覆盖
                if (trip.legs[legIdx].isMetroOnly() && stationByTap && stationAt > 0 && at - stationAt < 10 * 60_000L) return
                if (!trip.legs[legIdx].isMetroOnly()) {
                    val k = (legIdx + 1..last).firstOrNull { trip.legs[it].isMetroOnly() } ?: return
                    val firstAt = if (legIdx == 0 && boardedAt == 0L) at - rideMs(0) else at
                    onBoarded(manual = false, at = firstAt)
                    while (legIdx < k) onBoarded(manual = false, at = at)
                    boardedAt = at - rideMs(k - 1); alightAt = at; rideLearned = true
                }
                stationAt = at; stationByTap = true   // 刷卡时刻就是进站时刻，按它之后第一班车算上车
                Diag.mark(this, "刷卡进站，切到地铁段")
                kick()
            }
            TransitSignal.Kind.EXIT -> {
                val cur = trip.legs[legIdx]
                val prevMetro = legIdx > 0 && trip.legs[legIdx - 1].isMetroOnly()
                when {
                    // 最后一段地铁：出站 = 到了
                    cur.isMetroOnly() && legIdx == last && (riding || stationAt > 0) -> {
                        if (riding && !restored && boardedAt > 0) Learn.recordRide(this, trip.id, legIdx, ((at - boardedAt) / 60_000L).toInt())
                        Diag.mark(this, "刷卡出站，行程结束")
                        finish()
                        return
                    }
                    // 中间一段地铁还没切走就出站了：先切到下一段，再按出站时刻算换乘
                    cur.isMetroOnly() && legIdx < last && stationAt > 0 -> {
                        onBoarded(manual = false, at = nextTrainAfter(cur, stationAt) ?: stationAt)
                        markAlight(at)
                    }
                    prevMetro && alightAt == 0L -> markAlight(at)
                }
                kick()
            }
            TransitSignal.Kind.APPROACH -> Unit
            TransitSignal.Kind.ARRIVE -> {
                // 「准备下车」= 列车刚离开前一站、快到下车站了；只当锚点，不当下车
                if (arriveAnchorAt > 0 && at - arriveAnchorAt < 10 * 60_000L) return
                fun alightName(i: Int) = trip.legs[i].candidates.firstOrNull { it.alightName.isNotEmpty() }?.alightName
                fun matches(i: Int) = alightName(i)?.let { text.contains(it.removeSuffix("站")) } == true
                val cur = trip.legs[legIdx]
                // 我们还以为在站台等车：说明其实已经上车了，先按进站后第一班车切过去
                if (cur.isMetroOnly() && !riding && stationAt > 0 && matches(legIdx)) {
                    onBoarded(manual = false, at = nextTrainAfter(cur, stationAt) ?: stationAt)
                }
                val metroIdx = when {
                    riding && trip.legs[legIdx].isMetroOnly() && matches(legIdx) -> legIdx
                    !riding && legIdx > 0 && trip.legs[legIdx - 1].isMetroOnly() && alightAt == 0L && matches(legIdx - 1) -> legIdx - 1
                    else -> return
                }
                arriveAnchorAt = at
                TraceLog.event("arrive_anchor", "leg" to metroIdx, "expect" to (at + lastHopMs(metroIdx)))
                Diag.mark(this, "快到${alightName(metroIdx)}，按播报校准")
                if (metroIdx == legIdx && arriveAlertedAt == 0L) {
                    arriveAlertedAt = System.currentTimeMillis()
                    LiveNotifier.alert(this, "快到 ${alightName(legIdx)} 了", "准备下车", metro = true, id = 1005)
                }
                saveRun(deadlineAt)
                kick()
            }
        }
    }

    /** 换乘段：出站时刻就是下地铁时刻，顺便学这段地铁实际坐了多久 */
    private fun markAlight(at: Long) {
        alightAt = at; stepSensor(false)
        if (!rideLearned && !restored && boardedAt > 0) {
            rideLearned = true
            Learn.recordRide(this, trip.id, legIdx - 1, ((at - boardedAt) / 60_000L).toInt())
        }
        Diag.mark(this, "刷卡出站，开始换乘")
        saveRun(deadlineAt)
    }

    /** 用户点「结束」：这是真实到站/放弃的时刻，记录下来作为对照 */
    fun userFinish() {
        TraceLog.event("user_end", "leg" to legIdx, "riding" to riding)
        scope.launch(Dispatchers.Main) { loop?.cancel(); finish() }
    }

    private fun finish() {
        TraceLog.event("finish", "leg" to legIdx, "riding" to riding)
        TraceLog.stop()
        finishing = true
        clearRun()
        try { lm?.removeUpdates(listener) } catch (_: Exception) { }
        stepSensor(false)
        power = null; highSince = 0L; coolUntil = 0L
        LiveState.snapshot.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Diag.mark(this, "App 从最近任务里被划掉（服务应继续）")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (!finishing) { Diag.mark(this, "服务被系统停止（非主动结束）"); TraceLog.stop() }
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
        const val ACTION_DISMISSED = "com.busnotice.commute.DISMISSED"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val svc = CommuteService.instance
        when {
            intent.action == ACTION_BOARDED && svc != null -> svc.onBoarded()
            intent.action == ACTION_DISMISSED -> {
                // 被划掉（或被系统移除）：行程照常进行，胶囊先隐藏，等下一次提醒或打开 App 再出来
                Diag.mark(ctx, "胶囊被移除（划掉或系统移除），行程继续在后台")
                if (svc != null) LiveNotifier.hidden = true
            }
            intent.action == ACTION_STOP -> CommuteService.stop(ctx)
            else -> CommuteService.stop(ctx)
        }
    }
}
