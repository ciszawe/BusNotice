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
        stationAt = 0L; metroRideSec.clear(); metroRoutes.clear(); alightAt = 0L; fenceFixes.clear(); stillSince = 0L; lastFastAt = 0L; rideLearned = false; stopAlerted = ""
        legIdx = 0; boardedAt = 0L; nearSince = 0L; fastFixes = 0; alertedKey = null; planDone = false; planAlerted = ""
        maxStops.clear(); maxEta.clear()
        manualRun = manual; pendingBoardAt = 0L; pendingBoardLoc = null; boardCommitted = false; riding = false; transferAlerted = false; arriveAlertedAt = 0L
        speeds.clear(); routeHits.clear(); lastGpsFix = null
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
        stationAt = 0L
        if (manual && !riding && trip.legs[legIdx].isMetroOnly()) runCatching { learnMetro(trip.legs[legIdx]) }
        // 只学自动启动的行程：手动点开测试的不算习惯；先挂起，确认真的坐了一段才记（commitBoard）
        if (legIdx == 0 && boardedAt == 0L && !manualRun && !boardCommitted) {
            pendingBoardAt = now; pendingBoardLoc = best?.let { Location(it) }
        }
        if (legIdx >= trip.legs.size - 1) {
            if (riding) return            // 已经在车上：重复点「已上车」不做任何事
            if (alight == null) { finish(); return }
            riding = true; boardedAt = now; arriveAlertedAt = 0L
            val l = trip.legs[legIdx]
            ridingCand = l.candidates.firstOrNull { it.isMetro() }.takeIf { l.isMetroOnly() } ?: lastCand ?: l.candidates.firstOrNull()
            speeds.clear(); routeHits.clear()
            loop?.cancel()
            val dl = now + rideMs(trip.legs.size - 1) + 20 * 60_000L
            loop = scope.launch { runLoop(dl) }
            saveRun(dl)
            return
        }
        boardedAt = now
        legIdx++
        nearSince = 0L; fastFixes = 0; alertedKey = null; transferAlerted = false
        alightAt = 0L; rideLearned = false; stopAlerted = ""; stepSensor(false)
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
        if (riding) return rideTick(leg, loc, now)

        // 按位置纠正进度：人已经在后面某一段的上车站附近、离当前这段的站很远，说明前面几段已经坐完了（没识别到上车）
        if (loc != null && loc.accuracy < 150) jumpByLocation(loc, now)?.let { return it }

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
        val expectArrive = if (boardedAt > 0 && prev != null) boardedAt + rideMs(legIdx - 1) else 0L
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
            val by = when {
                // 出站：卫星定位恢复（地下收不到卫星）
                lastGpsFix?.let { now - it.time < 60_000 && it.time > boardedAt } == true &&
                    now >= expectArrive - 2 * 60_000L && (dist == null || dist < 1_500) -> "gps"
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
                alightAt = now; stepSensor(false); saveRun(deadlineAt)
                Log.i(TAG, "判断已下地铁：$by")
                if (by != "time" && !rideLearned) {
                    rideLearned = true
                    Learn.recordRide(this, trip.id, legIdx - 1, ((now - boardedAt) / 60_000L).toInt())
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
                g != null && now - g.time > 90_000 &&
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
            onMetro -> setPower(Power.OFF)
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

    /** 后面哪一段的上车站离现在位置 250 米内、当前段的站都在 600 米外，就直接跳到那一段；跳了返回下一次 tick 间隔 */
    private fun jumpByLocation(loc: Location, now: Long): Long? {
        fun near(l: Leg) = l.candidates.filter { !it.lat.isNaN() }
            .minOfOrNull { Geo.distanceM(loc.latitude, loc.longitude, it.lat, it.lng) } ?: Double.MAX_VALUE
        if (near(trip.legs[legIdx]) < 600) return null
        val k = (legIdx + 1 until trip.legs.size).firstOrNull { near(trip.legs[it]) <= 250 } ?: return null
        Diag.mark(this, "定位显示已到第 ${k + 1} 段上车站附近，跳过前面的段")
        // 第一段上车时间按「现在 - 这段通常乘车时长」估，供学习用；中间段直接过
        val firstAt = if (legIdx == 0 && boardedAt == 0L) now - rideMs(0) else now
        onBoarded(manual = false, at = firstAt)
        while (legIdx < k) onBoarded(manual = false, at = now)
        // 已经下车站在换乘点：不再按乘车时间倒数
        boardedAt = now - rideMs(k - 1); alightAt = now; rideLearned = true
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
            "${p.busLabel} $est${clock(now, p.busSec)}（换乘等约 ${Decision.mins(p.waitSec)} 分）" +
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

    /** 最后一段车上：快到下车站时提醒一次，到了就收起 */
    private fun rideTick(leg: Leg, loc: Location?, now: Long): Long {
        val dest = alight ?: run { finish(); return 60_000L }
        val rc = ridingCand
        val metro = rc?.isMetro() ?: leg.isMetroOnly()
        val name = rc?.alightName?.takeIf { it.isNotEmpty() }
            ?: leg.candidates.firstOrNull { it.alightName.isNotEmpty() }?.alightName ?: "下车站"
        val lineTitle = rc?.label() ?: leg.title()
        val d = loc?.let { Geo.distanceM(it.latitude, it.longitude, dest.first, dest.second) }
        val eta = boardedAt + rideMs(legIdx)
        val near = d != null && d <= 700
        val timeUp = now >= eta - 90_000 && (d == null || d < 1_500)
        if (arriveAlertedAt == 0L && (near || timeUp)) {
            arriveAlertedAt = now
            LiveNotifier.alert(this, "快到 $name 了", "准备下车", metro = metro, id = 1005)
        }
        if (arriveAlertedAt > 0 && ((d != null && d <= 150) || now - arriveAlertedAt > 5 * 60_000L)) {
            finish(); return 60_000L
        }
        val total = rideMs(legIdx).coerceAtLeast(60_000L)
        // 有定位时以距离为准（时间估短了也不会提前显示「快到了」），没定位才按时间
        val span = rc?.let { Geo.distanceM(it.lat, it.lng, dest.first, dest.second) }?.takeIf { it > 300 }
        val p = if (d != null && span != null) ((1 - d / span) * 100).toInt().coerceIn(0, 97)
            else ((now - boardedAt) * 100 / total).toInt().coerceIn(0, 100)
        val left = if (d != null && d > 1_500) (d / (if (metro) 550.0 else 350.0)).toInt().coerceAtLeast(1)
            else ((eta - now) / 60_000L).toInt()
        val short = when {
            arriveAlertedAt > 0 -> "下车"
            d != null && d < 1_500 -> "${d.toInt()}米"
            left > 0 -> "约${left}分"
            else -> "快到了"
        }
        val mr = if (metro) metroRoutes[legIdx] else null
        val text = if (mr != null && arriveAlertedAt == 0L) {
            val (next, n) = MetroRoutes.progress(mr, now - boardedAt, total)
            "下一站 $next · 还有 $n 站到 $name"
        } else "前往 $name" + (d?.let { " · 还有约 ${"%.1f".format(it / 1000)} 公里" } ?: "")
        val v = LiveNotifier.Visual("$lineTitle · 到 $name", text, short,
            if (trip.legs.size > 1) "${trip.name} · 最后一段" else trip.name,
            progress = p, metro = metro, trackerRes = if (metro) R.drawable.ic_metro else R.drawable.ic_bus, riding = true)
        LiveNotifier.update(this, LiveNotifier.build(this, v))
        LiveState.snapshot.value = LiveSnapshot(trip.id, trip.name, legIdx, trip.legs.size, lineTitle, short, text, null, p, null, riding = true)
        if (metro && boardedAt + total - now > 5 * 60_000L) { setPower(Power.OFF); return 60_000L }
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
        val sub = if (trip.legs.size > 1) "${trip.name} · 第 ${legIdx + 1}/${trip.legs.size} 段" else trip.name
        val arr = a.arrival
        val metro = c?.isMetro() == true || leg.isMetroOnly()
        val icon = if (metro) R.drawable.ic_metro else R.drawable.ic_bus

        if (connecting) {
            val prev = trip.legs[legIdx - 1]
            val total = rideMs(legIdx - 1)
            val p = if (total > 0) (100 - (expectArrive - now) * 100 / total).toInt() else 50
            val r = metroRoutes[legIdx - 1]
            if (r != null && now < expectArrive) {
                val (next, left) = MetroRoutes.progress(r, now - boardedAt, total)
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
            .putBoolean("riding", riding).putLong("alightAt", alightAt).putLong("deadline", deadline)
            .putString("ridingCand", ridingCand?.let { "${it.name}|${it.stopName}" } ?: "")
            .putBoolean("manual", manualRun)
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
        manualRun = p.getBoolean("manual", manualRun)
        pendingBoardAt = p.getLong("pendBoard", 0L); boardCommitted = p.getBoolean("boardDone", false)
        pendingBoardLoc = (p.getString("pendLoc", "") ?: "").split(',').takeIf { it.size == 2 }?.let { (a, b) ->
            val la = a.toDoubleOrNull(); val lo = b.toDoubleOrNull()
            if (la == null || lo == null) null else Location("saved").apply { latitude = la; longitude = lo }
        }
        rideLearned = true // 中断过的这趟不再学乘车时长，免得样本不准
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
                    if (!kicked || pr == LocationManager.GPS_PROVIDER) {
                        kicked = true
                        loop?.cancel(); loop = scope.launch { runLoop(deadlineAt) }
                    }
                }
            }
        }
    }

    fun userFinish() { scope.launch(Dispatchers.Main) { loop?.cancel(); finish() } }

    private fun finish() {
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
        if (!finishing) Diag.mark(this, "服务被系统停止（非主动结束）")
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
