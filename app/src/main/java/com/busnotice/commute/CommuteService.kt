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
import android.os.PowerManager
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
    // 仅供界面显示：当前（刚过）站、上车站、下车站名（没有站点数据时为 null）
    val curStop: String? = null,
    val fromStop: String? = null,
    val toStop: String? = null,
)

/**
 * 界面读的实时状态。内存里一份（StateFlow），另外存一份到本地（SharedPreferences）：
 * 进程被杀后重开 App、服务还没跑第一轮刷新时，首页用存下的这份先把看板显示出来。
 * 只是显示用的缓存，不参与任何判断。
 */
object LiveState {
    val snapshot = MutableStateFlow<LiveSnapshot?>(null)

    private const val PREFS = "live_snapshot"
    private const val SAVE_EVERY_MS = 15_000L
    private var lastSaved: LiveSnapshot? = null
    private var lastSavedAt = 0L

    /** 服务每次刷新时调用：更新内存，按需存盘（内容变了才写；内容没变最多 15 秒写一次） */
    fun publish(ctx: Context, s: LiveSnapshot, deadline: Long) {
        snapshot.value = s
        val now = System.currentTimeMillis()
        val same = lastSaved?.copy(updatedAt = 0L) == s.copy(updatedAt = 0L)
        if (same && now - lastSavedAt < SAVE_EVERY_MS) return
        lastSaved = s; lastSavedAt = now
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("snap", toJson(s).toString()).putLong("deadline", deadline).apply()
        }
    }

    /** 行程结束：删掉存下的那份 */
    fun clearPersisted(ctx: Context) {
        lastSaved = null; lastSavedAt = 0L
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
    }

    /** 中断恢复记录（run_state）里还没到期的行程：tripId；没有返回 null */
    fun activeRunTrip(ctx: Context): String? {
        val p = ctx.getSharedPreferences("run_state", Context.MODE_PRIVATE)
        val id = p.getString("trip", null) ?: return null
        return id.takeIf { p.getLong("deadline", 0) > System.currentTimeMillis() }
    }

    /** 读存下的那份：run_state 里同一趟行程还没到期才算数（tripId 不对就返回 null） */
    fun loadPersisted(ctx: Context, tripId: String? = activeRunTrip(ctx)): LiveSnapshot? {
        if (tripId == null) return null
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getLong("deadline", 0) <= System.currentTimeMillis()) return null
        val s = runCatching { fromJson(org.json.JSONObject(p.getString("snap", null) ?: return null)) }.getOrNull()
        return s?.takeIf { it.tripId == tripId }
    }

    /** 内存里是空的（进程刚重启）：用存下的那份补上；已经有就不动 */
    fun ensureRestored(ctx: Context, tripId: String? = activeRunTrip(ctx)) {
        if (snapshot.value != null) return
        val s = loadPersisted(ctx, tripId) ?: return
        snapshot.compareAndSet(null, s)
    }

    private fun toJson(s: LiveSnapshot) = org.json.JSONObject()
        .put("tripId", s.tripId).put("tripName", s.tripName).put("legIndex", s.legIndex).put("legCount", s.legCount)
        .put("legTitle", s.legTitle).put("headline", s.headline).put("detail", s.detail)
        .put("mode", s.mode?.name ?: "").put("progress", s.progress ?: -1).put("countdownTo", s.countdownTo ?: -1L)
        .put("riding", s.riding).put("updatedAt", s.updatedAt)
        .put("stopsPassed", s.stopsPassed ?: -1).put("stopsTotal", s.stopsTotal ?: -1)
        .put("curStop", s.curStop ?: "").put("fromStop", s.fromStop ?: "").put("toStop", s.toStop ?: "")

    private fun fromJson(o: org.json.JSONObject) = LiveSnapshot(
        tripId = o.getString("tripId"), tripName = o.optString("tripName"),
        legIndex = o.optInt("legIndex"), legCount = o.optInt("legCount", 1), legTitle = o.optString("legTitle"),
        headline = o.optString("headline"), detail = o.optString("detail"),
        mode = o.optString("mode").takeIf { it.isNotEmpty() }?.let { m -> runCatching { Decision.Mode.valueOf(m) }.getOrNull() },
        progress = o.optInt("progress", -1).takeIf { it >= 0 },
        countdownTo = o.optLong("countdownTo", -1L).takeIf { it >= 0 },
        riding = o.optBoolean("riding"), updatedAt = o.optLong("updatedAt", 0L),
        stopsPassed = o.optInt("stopsPassed", -1).takeIf { it >= 0 },
        stopsTotal = o.optInt("stopsTotal", -1).takeIf { it >= 0 },
        curStop = o.optString("curStop").ifEmpty { null },
        fromStop = o.optString("fromStop").ifEmpty { null },
        toStop = o.optString("toStop").ifEmpty { null },
    )
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
        private const val EXTRA_RESTART = "restart"
        private const val MANUAL_RUN_MS = 30 * 60_000L
        private const val UNDO_MS = 2 * 60_000L
        /** 手点「已上车」后 2 分钟内：按钮变「撤销」，再点是撤回不是跳下一段 */
        val undoUntil = MutableStateFlow(0L)
        fun undoActive() = undoUntil.value > System.currentTimeMillis()
        private const val NEAR_STOP_M = 120.0
        @Volatile var instance: CommuteService? = null

        /**
         * restart = 用户明确要「重新开始 / 切换到这一趟」：才允许清掉正在跑的进度。
         * 不带 restart 时：同一趟正在跑 → 不动；别的趟正在跑 → 自动启动直接忽略，手动启动交给界面先确认。
         */
        fun start(ctx: Context, tripId: String, manual: Boolean, fresh: Boolean = false, restart: Boolean = false) {
            val i = Intent(ctx, CommuteService::class.java)
                .putExtra(EXTRA_TRIP_ID, tripId)
                .putExtra(EXTRA_MANUAL, manual)
                .putExtra("fresh", fresh)
                .putExtra(EXTRA_RESTART, restart)
            ctx.startForegroundService(i)
            Scheduler.markRan(ctx, tripId)
        }

        /** 正在跑的行程 id（服务活着且行程没结束）；没有返回 null */
        fun runningTripId(): String? = instance?.runningId()

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
            LiveState.clearPersisted(ctx); LiveState.snapshot.value = null
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
    private var busFastLogged = 0L
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
        // 是什么拉起的这一趟（闹钟 / 离开出发地 / 刷卡 / 围栏…）：取启动前最后一条诊断，写进行程记录，方便查误启动
        val why = Diag.last(this)
        val manual = intent?.getBooleanExtra(EXTRA_MANUAL, false) ?: false
        // 已经有一趟在跑：不许被重复点击 / 自动启动悄悄覆盖掉进度
        val curId = runningId()
        if (intent != null && curId != null && !intent.getBooleanExtra(EXTRA_RESTART, false)) {
            val same = curId == id
            TraceLog.event("start_ignored", "same" to same, "manual" to manual, "fresh" to intent.getBooleanExtra("fresh", false))
            Diag.mark(this, if (same) "行程已在进行中，忽略重复启动" else "另一趟行程进行中，忽略这次启动")
            if (same) LiveNotifier.unhide(this)
            return START_STICKY
        }
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

        instance = this
        LiveNotifier.hidden = false
        if (intent == null) Diag.mark(this, "服务被系统杀掉后自动重启")
        else if (!manual) Diag.mark(this, "已自动启动")
        val restart = intent?.getBooleanExtra(EXTRA_RESTART, false) == true
        trip = t
        finishing = false
        restored = false
        startedAt = System.currentTimeMillis()
        // 用户手动「重新开始 / 切换」：常常人已经在半路（甚至在地下没信号），
        // 允许用最近 10 分钟内的定位来判断现在在第几段，而不是一律从第一段的上车站算起
        trustFixSince = startedAt - if (restart) 10 * 60_000L else 30_000L
        runCatching { TraceLog.start(this, t.id, t.name) }
        TraceLog.event("run", "manual" to manual, "restart" to (intent == null), "userRestart" to restart, "why" to why)
        TraceLog.onCell = { snap -> scope.launch(Dispatchers.Main) { runCatching { onCell(snap) }.onFailure { Log.w(TAG, "基站处理失败", it) } } }
        walkFixes.clear(); firstWalkDone = false; userRestarted = restart
        metroGate.reset(); cellExitAt = 0L; if (metroRideOn) fpRec.finish(); metroRideOn = false; busMovingOn = false; tacLearner = null
        stationAt = 0L; metroRideSec.clear(); metroRoutes.clear(); alightAt = 0L; fenceFixes.clear(); stillSince = 0L; lastFastAt = 0L; rideLearned = false; stopAlerted = ""
        stationByTap = false; arriveAnchorAt = 0L; metroAnchorIdx = -1; metroAnchorAt = 0L
        deferredTapAt = 0L; deferredTapKind = null
        boardSnap = null; undoUntil.value = 0L; pendingLearnLeg = -1
        metroExitSeen = false; metroExitAt = 0L
        joinLeg = -1; joinSkip = 0; joinScale = 1.0; joinOrigStop = ""; joinStop = ""; joinCandIdx = -1; joinCandAt = 0L; joinCandFixT = 0L; pendingJoin = null
        placeStart = null; arrived = false; lastPlace = null; placeSince = 0L; startLabel = null; busHitTimes.clear(); tail = false; tailFixes.clear(); startVoted = false
        legIdx = 0; boardedAt = 0L; nearSince = 0L; fastFixes = 0; alertedKey = null; planDone = false; planAlerted = ""
        maxStops.clear(); maxEta.clear()
        manualRun = manual; pendingBoardAt = 0L; pendingBoardLoc = null; boardCommitted = false; riding = false; transferAlerted = false; arriveAlertedAt = 0L
        speeds.clear(); routeHits.clear(); lastGpsFix = null
        resetBusRide(); busRoute = null; busRouteLine = ""
        power = null; highSince = 0L; coolUntil = 0L
        setPower(Power.HIGH) // 预热：先拿到一个准的位置
        holdCpu(true)
        scope.launch(Dispatchers.IO) { prefetchMetroStops(t); prefetch(t); prefetchLastMetroByName(t) }
        loop?.cancel()
        // 围栏拉起的是一趟全新的行程：不接中断前的进度
        val fresh = intent?.getBooleanExtra("fresh", false) == true || restart
        if (fresh) clearRun()
        val resumedDl = if (fresh) null else restoreRun(t)
        // 看板缓存：接着中断前那趟跑就先用存下的看板顶上（第一轮刷新前首页不空着）；新的一趟就删掉旧缓存
        if (resumedDl != null) LiveState.ensureRestored(this, t.id) else LiveState.clearPersisted(this)
        loop = scope.launch { runLoop(resumedDl ?: if (fresh) freshDeadline(t) else deadline(t, manual)) }
        // 由刷卡弹窗拉起的新行程：把那次刷卡（3 分钟内）补交给行程，直接按进站 / 上车处理
        if (resumedDl == null) TransitSignal.recent(this, 3 * 60_000L)?.let { (k, txt, at) ->
            if (k == TransitSignal.Kind.ENTER || k == TransitSignal.Kind.TAP) onTransitSignal(k, txt, at)
        }
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
        Learn.rideMin(this, trip.id, i)?.let { return (it * 60_000L * (if (i == joinLeg) joinScale else 1.0)).toLong() }
        metroRideSec[i]?.let { return it * 1000L }
        return trip.legs[i].rideMin * 60_000L
    }

    /** 地铁段按站数估的乘车秒数（每站约 2 分 20 秒，含停站） */
    private val metroRideSec = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val SEC_PER_STOP = 140

    /** 每段地铁途经的站（站名 + 坐标），拿来显示「下一站」 */
    private val metroRoutes = java.util.concurrent.ConcurrentHashMap<Int, ChelaileApi.MetroRoute>()

    /**
     * 最后一段是地铁、下车站只填了名字没有坐标时（编辑页改站名会清掉坐标）：
     * prefetch() 已按站名查到 alight 坐标，用它补查途经站，显示「还有 N 站」、按站数估到站。
     */
    private fun prefetchLastMetroByName(t: Trip) {
        val i = t.legs.size - 1
        val leg = t.legs.getOrNull(i) ?: return
        if (!leg.isMetroOnly() || metroRoutes[i] != null) return
        val c = leg.candidates.firstOrNull { it.isMetro() } ?: return
        val (la, ln) = alight ?: return
        val name = c.alightName.ifEmpty { "下车站" }
        MetroRoutes.get(this, c, name, la, ln)?.let { r ->
            metroRoutes[i] = r
            metroRideSec[i] = r.durSec
            TraceLog.event("metro_route", "leg" to i, "hops" to r.hops, "by" to "name")
        }
    }

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
        val dirs = metroDirs[dirKey(c)]?.filter { c.metroDir.isEmpty() || it.dest == c.metroDir } ?: return null
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
        if (joinLeg >= 0) return   // 半路上车：上车时刻不代表平时什么时候出发，不学
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
    private fun learnMetro(leg: Leg, nowSec: Int = LocalTime.now().toSecondOfDay()) {
        val c = leg.candidates.firstOrNull { it.isMetro() && it.metroDir.isNotEmpty() } ?: return
        val d = metroDirs["${c.stopName}|${c.name}"]?.firstOrNull { it.dest == c.metroDir } ?: return
        val weekday = LocalDate.now().dayOfWeek.value <= 5
        val r = Decision.metroResidual(nowSec - 30, d.firstMin, d.lastMin, weekday, c.peakHw(), c.offPeakHw()) ?: return
        val peak = Decision.headwayAt(nowSec / 60, weekday, 1, 2) == 1
        Learn.recordMetro(this, Learn.metroKey(c, peak), r)
    }

    /** 手点「已上车」前的进度，撤销时整个放回去 */
    private data class BoardSnap(
        val legIdx: Int, val boardedAt: Long, val riding: Boolean, val stationAt: Long, val stationByTap: Boolean,
        val alightAt: Long, val rideLearned: Boolean, val arriveAnchorAt: Long, val arriveAlertedAt: Long,
        val pendingBoardAt: Long, val pendingBoardLoc: Location?, val ridingCand: Candidate?,
        val metroAnchorIdx: Int, val metroAnchorAt: Long, val nearSince: Long, val transferAlerted: Boolean, val deadlineAt: Long,
    )
    private var boardSnap: BoardSnap? = null
    // 地铁段手点的时刻：撤销窗口过了才拿去学，误点撤回的不学
    private var pendingLearnLeg = -1
    private var pendingLearnSec = 0

    private fun undoBoard() {
        val b = boardSnap ?: return
        boardSnap = null; undoUntil.value = 0L; pendingLearnLeg = -1
        legIdx = b.legIdx; boardedAt = b.boardedAt; riding = b.riding; stationAt = b.stationAt; stationByTap = b.stationByTap
        alightAt = b.alightAt; rideLearned = b.rideLearned; arriveAnchorAt = b.arriveAnchorAt; arriveAlertedAt = b.arriveAlertedAt
        pendingBoardAt = b.pendingBoardAt; pendingBoardLoc = b.pendingBoardLoc; ridingCand = b.ridingCand
        metroAnchorIdx = b.metroAnchorIdx; metroAnchorAt = b.metroAnchorAt; nearSince = b.nearSince; transferAlerted = b.transferAlerted
        alertedKey = null; speeds.clear(); routeHits.clear()
        TraceLog.event("board_undo", "leg" to legIdx)
        Diag.mark(this, "撤销了手点的「已上车」，回到第 ${legIdx + 1} 段")
        loop?.cancel()
        deadlineAt = b.deadlineAt
        loop = scope.launch { runLoop(b.deadlineAt) }
        saveRun(b.deadlineAt)
    }

    /** 撤销窗口过了：手点的地铁上车时刻才算数 */
    private fun flushPendingLearn(now: Long) {
        if (undoUntil.value > now) return
        boardSnap = null
        val i = pendingLearnLeg
        if (i < 0) return
        pendingLearnLeg = -1
        runCatching { learnMetro(trip.legs[i], pendingLearnSec) }
    }

    /** 「已上车」按钮或速度判断：进入下一段；最后一段则进入「去下车站」，再按一次结束 */
    fun onBoarded(manual: Boolean = true, at: Long = System.currentTimeMillis()) {
        if (!::trip.isInitialized) { finish(); return }
        if (tail) return
        // 手点后 2 分钟内再点 = 撤销（防误点 / 连点直接跳两段）
        if (manual && boardSnap != null && undoActive()) { undoBoard(); return }
        val now = at
        TraceLog.event("boarded", "leg" to legIdx, "manual" to manual, "at" to at, "riding" to riding)
        if (manual) {
            if (legIdx >= trip.legs.size - 1 && riding) return
            boardSnap = BoardSnap(legIdx, boardedAt, riding, stationAt, stationByTap, alightAt, rideLearned, arriveAnchorAt, arriveAlertedAt,
                pendingBoardAt, pendingBoardLoc, ridingCand, metroAnchorIdx, metroAnchorAt, nearSince, transferAlerted, deadlineAt)
            undoUntil.value = System.currentTimeMillis() + UNDO_MS
        } else if (boardSnap != null) {
            // 自动判断接着推进了：之前那次手点就不再能撤
            boardSnap = null; undoUntil.value = 0L
        }
        stationAt = 0L
        if (manual && !riding && trip.legs[legIdx].isMetroOnly()) { pendingLearnLeg = legIdx; pendingLearnSec = LocalTime.now().toSecondOfDay() }
        // 只学自动启动的行程：手动点开测试的不算习惯；先挂起，确认真的坐了一段才记（commitBoard）
        if (legIdx == 0 && boardedAt == 0L && !manualRun && !boardCommitted) {
            pendingBoardAt = now; pendingBoardLoc = best?.let { Location(it) }
        }
        if (legIdx >= trip.legs.size - 1) {
            if (riding) return            // 已经在车上：重复点「已上车」不做任何事
            // 下车站坐标还在后台取（刚启动/刚恢复）时别结束；整趟根本没填下车站才结束
            if (alight == null && trip.legs[legIdx].candidates.none { it.alightName.isNotEmpty() || !it.alightLat.isNaN() }) { finish(); return }
            riding = true; boardedAt = now; arriveAlertedAt = 0L; resetBusRide(); metroAnchorIdx = -1; metroAnchorAt = 0L
            val l = trip.legs[legIdx]
            busBoardGuessed = !manual && !boardingByTap && !l.isMetroOnly()
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
        deferredTapAt = 0L; deferredTapKind = null
        if (trip.legs[legIdx - 1].isMetroOnly()) { metroExitSeen = false; metroExitAt = 0L }
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

    /**
     * 市区公交走走停停，连续 3 次 ≥6 m/s 太苛刻：最近 5 次 GPS 里 ≥3 次 ≥4.5 m/s、至少 1 次 ≥6 m/s，
     * 且 ≥4 次贴线（最新一次必须贴线）就算在车上。
     */
    private fun busRideLikely(): Boolean {
        if (speeds.size < 4 || routeHits.size < 4) return false
        val sp = speeds.toList(); val r = routeHits.toList()
        return sp.count { it >= 4.5f } >= 3 && sp.maxOrNull()!! >= 6f && r.count { it } >= 4 && r.last()
    }

    /** 到这段公交线路（相邻站连成折线）的最近距离，米；没线路数据返回 null */
    private fun lineDistM(loc: Location, leg: Leg): Double? {
        val lines = leg.candidates.filter { !it.isMetro() }.mapNotNull { routes[it.lineId] }.filter { it.size >= 2 }
        if (lines.isEmpty()) return null
        val k = Math.cos(Math.toRadians(loc.latitude)) * 111_320.0
        fun xy(p: Pair<Double, Double>) = (p.second - loc.longitude) * k to (p.first - loc.latitude) * 110_540.0
        var best = Double.MAX_VALUE
        for (pts in lines) for (i in 0 until pts.size - 1) {
            val (ax, ay) = xy(pts[i]); val (bx, by) = xy(pts[i + 1])
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            best = minOf(best, Math.sqrt(px * px + py * py))
        }
        return best
    }

    private val busHitTimes = ArrayDeque<Long>()

    /**
     * H1：当前还是地铁段（没进站 / 没上车），人却在地面以车速贴着下一段公交线路走（GPS 连续 3 次、跨 60 秒以上，离线路 ≤ 80 米）
     * → 没坐地铁，直接改坐了这路公交（或从别的站上的车）：跳过地铁段，按第一次贴线的时刻算公交上车。
     */
    private fun busJumpCheck(loc: Location, spd: Float): Boolean {
        val nk = legIdx + 1
        if (!trip.legs[legIdx].isMetroOnly() || inMetroRide() || nk >= trip.legs.size || trip.legs[nk].isMetroOnly()) { busHitTimes.clear(); return false }
        val d = lineDistM(loc, trip.legs[nk])
        if (spd >= 4f && d != null && d <= 80.0) busHitTimes.addLast(loc.time) else { busHitTimes.clear(); return false }
        if (busHitTimes.size < 3 || busHitTimes.last() - busHitTimes.first() < 60_000L) return false
        val at = busHitTimes.first(); busHitTimes.clear()
        val now = System.currentTimeMillis()
        TraceLog.event("bus_jump", "from" to legIdx, "to" to nk, "at" to at, "distM" to (d ?: 0.0).toInt())
        Diag.mark(this, "在地面上以车速沿第 ${nk + 1} 段公交走，跳过地铁，判断已上公交")
        onBoarded(manual = false, at = at)           // 地铁段：当作「坐过」
        boardedAt = at; alightAt = at; rideLearned = true; metroExitSeen = true; stationAt = 0L
        joinLeg = nk                                   // 半路上的车：不学这段乘车时长
        onBoarded(manual = false, at = at)           // 公交段：上车
        saveRun(deadlineAt); kick()
        return true
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
        if (tail) return tailTick(System.currentTimeMillis())
        commitBoard(System.currentTimeMillis())
        flushPendingLearn(System.currentTimeMillis())
        retryDeferredTap(System.currentTimeMillis())
        runCatching { checkFirstWalk() }
        val leg = trip.legs[legIdx]
        val prev = trip.legs.getOrNull(legIdx - 1)
        val now = System.currentTimeMillis()
        val fresh = best?.takeIf { it.accuracy < 200 && now - it.time < 120_000 }
        // 地铁里网络定位误差大（几百米到一公里），坐地铁时放宽到 800 米也拿来用
        if (riding) return rideTick(leg, fresh ?: best?.takeIf { leg.isMetroOnly() && it.accuracy < 800 && now - it.time < 90_000 }, now)
        // 第一段还没上车（在家/公司里不动）：没有新定位时试系统缓存位置，再不行且手机一直没动就沿用上一次的好定位
        val loc = fresh ?: if (legIdx == 0 && boardedAt == 0L) walkPhaseLoc(now) else null

        pendingJoin?.let { (i, j) -> if (metroRoutes[i] != null) { pendingJoin = null; applyJoin(i, j) } }
        // 半路上车：人在这段地铁沿线后面某站附近（不是配置的上车站）→ 从那站算起
        if (fresh != null) tryJoin(fresh, legIdx, now, byTap = false)
        else if (stationAt > 0 && !stationByTap) {
            // 基站已认出在地下、还没上车：没有准定位时退一步用 500 米内的网络定位
            best?.takeIf { it.accuracy <= 500 && now - it.time < 90_000 }?.let { tryJoin(it, legIdx, now, byTap = false, coarse = true) }
        }
        // 按位置纠正进度：人已经在后面某一段的上车站附近、离当前这段的站很远，说明前面几段已经坐完了（没识别到上车）
        jumpByLocation(now)?.let { return it }

        val dists = leg.candidates.map { c -> loc?.let { Geo.distanceM(it.latitude, it.longitude, c.lat, c.lng) } }
        val nearest = dists.filterNotNull().minOrNull()
        // 还在上一段地铁上（没确认下车）：站里的网络定位忽近忽远，单凭一次定位贴近下一段站牌就切换显示会来回跳。
        // 只认精度 ≤ 100 米、且已经到了快下车的时间窗，才算到了换乘点（同时记为下车）
        val metroUnsure = prev?.isMetroOnly() == true && boardedAt > 0 && alightAt == 0L
        val prevArrive = if (metroUnsure) (anchoredArrive(legIdx - 1) ?: (boardedAt + rideMs(legIdx - 1))) else 0L
        val nearOk = !metroUnsure || (loc != null && loc.accuracy <= 100 && now >= prevArrive - 5 * 60_000L)
        if (metroUnsure && nearOk && nearest != null && nearest <= 300) {
            markAlight(now, "near")
        }
        if (nearest != null && nearest <= NEAR_STOP_M && nearSince == 0L && nearOk) {
            nearSince = now
            // 换乘到了：记下上一段实际坐了多久，下次推算换乘更准
            if (prev != null && boardedAt > 0 && !rideLearned) {
                rideLearned = true
                learnRide(legIdx - 1, ((now - boardedAt) / 60_000L).toInt() - prev.transferWalkMin)
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
            // 站里经常一阵子没有可用定位：没定位不算「离开围栏」，只有定位明确在围栏外才清零
            if (inFence && (speed == null || speed < 2.0)) { if (stillSince == 0L) stillSince = now } else if (dist != null && !inFence) stillSince = 0L
            val recentlyFast = now - lastFastAt < 60_000
            val noLocFor = fenceFixes.lastOrNull()?.let { now - it.time } ?: Long.MAX_VALUE
            // 加速度：列车停稳（静止）之后持续走路 ≥ 20 秒 = 下车往外走；只当佐证，要配合时间窗和位置
            val cellStill = TraceLog.cellStillMs(now)
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
                window && stillSince > 0 && now - stillSince >= 90_000 && (inFence || dist == null) -> "fence+still"
                // 服务基站 150 秒以上没换：列车在跑时一两分钟就换一次，长时间不换 = 人已经停下来了（下车在站里等）
                window && cellStill != null && cellStill >= 150_000 && TraceLog.cellSince > boardedAt + 3 * 60_000L &&
                    !recentlyFast && (inFence || dist == null || noLocFor > 60_000L) -> "cell_still"
                // 完全没有位置时才单靠步数
                window && noLocFor > 3 * 60_000L && stepOn && steps >= 40 -> "steps"
                // 兜底：过了预计时间 3 分钟；但定位显示离换乘站还远且在动，说明晚点了，最多再等 10 分钟
                now >= expectArrive + 3 * 60_000L && !(dist != null && dist > 1_500 && recentlyFast &&
                    now < expectArrive + 10 * 60_000L) -> "time"
                else -> null
            }
            if (by != null) {
                // 按加速度判断时，下车时刻是开始走路的时刻（换乘步行从那时算）
                val at = when (by) {
                    "motion" -> walkStart ?: now
                    // 基站不再变的那一刻就是列车停下、人下车的时刻
                    "cell_still" -> TraceLog.cellSince.coerceIn(boardedAt, now)
                    else -> now
                }
                alightAt = at; stepSensor(false); saveRun(deadlineAt)
                Log.i(TAG, "判断已下地铁：$by")
                TraceLog.event("alight_detect", "leg" to legIdx - 1, "by" to by, "at" to at)
                if (by != "time" && !rideLearned) {
                    rideLearned = true
                    learnRide(legIdx - 1, ((at - boardedAt) / 60_000L).toInt())
                }
            }
        }
        // 上一段是地铁：是否还在车上只看有没有确认下车，不随单次定位远近来回切换（之前会在公交和地铁之间跳）
        val connecting = expectArrive > 0 && nearSince == 0L &&
            (if (fromMetro) alightAt == 0L else (nearest == null || nearest > 300) && now < expectArrive + 5 * 60_000L)
        val connectSec = if (connecting) (((expectArrive - now) / 1000).toInt().coerceAtLeast(0) + leg.transferWalkMin * 60) else 0

        // 推断进站：在站口附近待了两分钟，或最后一次卫星定位在站口 500 米内、之后 90 秒没信号。
        // 进站 ≠ 上车：按进站后第一班赶得上的车算上车时刻，那班车开走了才切到下一段。
        if (leg.isMetroOnly() && stationAt == 0L) {
            val g = lastGpsFix
            stationAt = when {
                nearSince > 0 && now - nearSince > 120_000 -> nearSince
                // 最后一次 GPS 要贴着站口（200 米），且不是还在出发地（公司 / 家离车站近，出门 GPS 一停就会误判进站）
                g != null && g.time >= startedAt - 30_000 && now - g.time > 90_000 &&
                    leg.candidates.any { Geo.distanceM(g.latitude, g.longitude, it.lat, it.lng) <= 200 } &&
                    walkFixes.firstOrNull().let { o -> o == null || Geo.distanceM(g.latitude, g.longitude, o.lat, o.lng) > 150 } -> g.time
                else -> 0L
            }
        }
        if (leg.isMetroOnly() && stationAt > 0) {
            val train = nextTrainAfter(leg, stationAt) ?: (stationAt + 4 * 60_000L)
            // 所有地铁段（上班最后一段、下班第一段）都一样：进站后先「等车」，有证据真的坐上了才切过去
            metroBoardAt(leg, train, now)?.let { onBoarded(manual = false, at = it); return 1_000L }
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
                            rt?.let { BusSampler.observe(applicationContext, c, it, "trip") }
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
        val a0 = Decision.decide(options, connecting)
        // 离到换乘站还有 10 分钟以上：实时到站那么远不准，不提赶不赶得上，只给预计到换乘站的时间
        val a = if (connecting && prev != null && expectArrive - now > 10 * 60_000L) {
            val at = prev.candidates.firstOrNull { it.alightName.isNotEmpty() }?.alightName ?: "换乘站"
            val hhmm = java.time.Instant.ofEpochMilli(expectArrive).atZone(ZoneId.systemDefault()).toLocalTime()
                .let { String.format("%02d:%02d", it.hour, it.minute) }
            val nextLine = a0.option?.candidate?.label() ?: leg.title()
            Decision.Advice(Decision.Mode.CONNECT, a0.option, null, a0.walkSec, 0, "${hhmm}到",
                "预计 $hhmm 到 $at · 之后换 $nextLine，快到时再看赶哪班")
        } else a0
        a.option?.candidate?.let { lastCand = it }
        // 先坐地铁再换公交、还没出发：倒推「最晚几点走，到换乘站刚好赶上公交」
        val plan = if (!planDone && legIdx == 0 && boardedAt == 0L && nearSince == 0L && leg.isMetroOnly())
            runCatching { planTransfer(leg, options, secOfDay, loc) }.getOrNull() else null
        if (plan != null) return planTick(plan, leg, a, now)
        val v = visual(a, leg, connecting, expectArrive, now)
        LiveNotifier.update(this, LiveNotifier.build(this, v))
        // 换乘中还在地铁上：看板也给站点圆点和站名（和胶囊同一算法）
        val cr = if (connecting && prev?.isMetroOnly() == true && now < expectArrive) metroRoutes[legIdx - 1] else null
        val cp = cr?.let { connectPassed(it, rideMs(legIdx - 1), now) }
        LiveState.publish(this, LiveSnapshot(
            trip.id, trip.name, legIdx, trip.legs.size, leg.title(), v.short, v.text, a.mode, v.progress, v.countdownTo,
            stopsPassed = cp, stopsTotal = cr?.hops?.coerceAtLeast(1),
            curStop = if (cr != null && cp != null) cr.stations[cp].name else null,
            fromStop = cr?.stations?.first()?.name, toStop = cr?.stations?.last()?.name,
        ), deadlineAt)

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
        // 倒计时只在没有新鲜定位、或定位也说已经不远时才算（10/29 早：离换乘站还有 3.2 公里就按倒计时弹了）
        val tFresh = loc != null && now - loc.time < 120_000 && loc.accuracy < 200
        val timeOk = connecting && now >= expectArrive - 180_000 && !(tFresh && nearest != null && nearest > 800)
        if (legIdx > 0 && boardedAt > 0 && !transferAlerted && ((nearest != null && nearest <= 500) || timeOk)) {
            TraceLog.event("transfer_alert", "nearest" to nearest?.toInt(), "byTime" to timeOk)
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
        val last = best?.takeIf { it.accuracy < 300 && it.time >= trustFixSince } ?: return null
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

    /**
     * 最后一段地铁：进站后什么时候算上车。
     * 1. 定位（地下网络定位也行，精度 1 公里内）已经离开上车站 500 米以上（扣掉误差）= 车开了；
     * 2. 兜底：赶得上的那班车过去 6 分钟、且 1 分钟内没在走路；再晚到 12 分钟无论如何切。
     * 上车时刻 = max(那班车, 最后一段持续走路结束)，不早于人还在走的时候。
     */
    /**
     * 地铁段（不论第几段）：进站后先「等车」，有证据真的坐上了才切过去，上车时刻按列车真正开动来记。
     * 证据（按可靠程度）：
     *  1. 定位离开上车站 500 米以上（精度 ≤ 1000 米）；
     *  2. 加速度显示「最后一次走动（跨进车门）之后连续 90 秒在晃」+ 那之后服务基站换了 → 列车开了；
     *  3. 兜底：预计班次过去 6 分钟且 1 分钟内没在走路；最晚 12 分钟一定切。
     * 上车时刻优先用 2 里「开始连续晃动」那一刻；没有就用 max(预计班次, 最后一次走动)。
     */
    private fun metroBoardAt(leg: Leg, train: Long, now: Long): Long? {
        val ws = TraceLog.recentWindows()
        val walkEnd = AccMotion.lastWalkEnd(ws, stationAt, now) ?: stationAt
        val dep = AccMotion.departureSince(ws, walkEnd, now)
        val fallbackAt = dep ?: maxOf(train, walkEnd)
        val st = leg.candidates.firstOrNull { it.isMetro() }
        val b = best
        if (st != null && b != null && b.time > stationAt && b.accuracy <= 1_000 &&
            Geo.distanceM(b.latitude, b.longitude, st.lat, st.lng) - b.accuracy > 500) {
            // 可能是从沿线后面某站进的：先等最多 90 秒确认半路上车，别急着按「离开上车站」判上车
            if (joinLeg < 0 && joinCandIdx >= 0 && now - joinCandAt < 90_000L) return null
            val at = minOf(fallbackAt, b.time)
            TraceLog.event("metro_board", "by" to "moved", "at" to at, "dep" to dep, "walkEnd" to walkEnd)
            return at
        }
        if (dep != null) {
            val cc = TraceLog.cellChangeAfter(maxOf(dep + 30_000L, walkEnd + 20_000L))
            if (cc != null && cc <= now) {
                TraceLog.event("metro_board", "by" to "cell", "at" to dep, "cell" to cc, "walkEnd" to walkEnd)
                return dep
            }
        }
        // H2：不用刷卡也能判——磁场持续大幅抖动 = 列车在跑（只看停下走路之后的；要已经持续 1 分钟以上）
        TraceLog.magTrainAt(maxOf(walkEnd, stationAt))?.takeIf { now - it >= 60_000L }?.let { at ->
            TraceLog.event("metro_board", "by" to "mag", "at" to at, "walkEnd" to walkEnd)
            return at
        }
        if ((now >= train + 6 * 60_000L && now - walkEnd > 60_000L) || now >= train + 12 * 60_000L) {
            TraceLog.event("metro_board", "by" to "time", "at" to fallbackAt, "dep" to dep, "walkEnd" to walkEnd)
            return fallbackAt
        }
        return null
    }

    private var planAlerted = ""
    private var planDone = false   // 提醒过「该走了」就不再倒推，改回正常的地铁进站显示

    /** 第一段地铁 + 第二段有公交时，算出发方案；条件不满足返回 null（照常显示地铁） */
    private suspend fun planTransfer(leg: Leg, options: List<Decision.Option>, secOfDay: Int, loc: Location?): List<Decision.Plan>? {
        val next = trip.legs.getOrNull(1) ?: return null
        val buses = next.candidates.filter { it.type == Candidate.Type.BUS }
        if (buses.isEmpty()) return null
        val c = leg.candidates.first()
        val dirs = metroDirs[dirKey(c)]?.filter { c.metroDir.isEmpty() || it.dest == c.metroDir } ?: return null
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
                    rt?.let { BusSampler.observe(applicationContext, b, it, "plan") }
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
        LiveState.publish(this, LiveSnapshot(
            trip.id, trip.name, legIdx, trip.legs.size, leg.title(), short, text, a.mode, null, v.countdownTo,
        ), deadlineAt)
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

    // ---------- 静止时沿用定位（只给第一段上车前的步行估算用） ----------
    private val still = AccMotion.StillTracker()
    private var reuseLoggedFix = 0L
    private val REUSE_MAX_MS = 15 * 60_000L

    /**
     * 当前没有新鲜定位时（不动就没有定位回调）：
     * 1. 取 FUSED/NETWORK/GPS 的系统缓存位置，精度 <200 米里最新的一个，比现有的新就换上；
     * 2. 还是不新鲜：加速度显示从那次定位起手机一直没动（没有持续走路/坐车），15 分钟内照样当作当前位置。
     * 不改 best 以外的任何状态，也不用于车上和下车判断。
     */
    @SuppressLint("MissingPermission")
    private fun walkPhaseLoc(now: Long): Location? {
        val m = lm ?: getSystemService(LocationManager::class.java)?.also { lm = it }
        if (m != null) {
            val cached = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { pr -> runCatching { m.getLastKnownLocation(pr) }.getOrNull() }
                .filter { it.accuracy < 200 && it.time <= now + 5_000 }
                .maxByOrNull { it.time }
            val cur = best
            if (cached != null && (cur == null || cached.time > cur.time)) { best = cached; TraceLog.loc(cached) }
        }
        val b = best?.takeIf { it.accuracy < 200 } ?: return null
        val age = now - b.time
        if (age < 120_000) return b
        if (age > REUSE_MAX_MS) return null
        still.feed(TraceLog.recentWindows())
        if (!still.stillSince(b.time, now)) return null
        if (reuseLoggedFix != b.time) {
            reuseLoggedFix = b.time
            TraceLog.event("loc_reuse", "age" to age / 1000, "acc" to b.accuracy.toInt())
        }
        return b
    }

    // 最后一段地铁：靠得住的定位（地下网络定位精度 300 米内）贴着某一站时，记下「这时在第几站」，之后按每站时间往后推
    private var metroAnchorIdx = -1
    private var metroAnchorAt = 0L

    /** 地铁已过几站（0..hops-1）和预计到下车站的时刻；没有途经站数据返回 null */
    /** 换乘中（还在上一段地铁上）已过几站：有「准备下车」锚点就是倒数第二站，否则按已坐时间 */
    private fun connectPassed(r: ChelaileApi.MetroRoute, total: Long, now: Long): Int {
        val hops = r.hops.coerceAtLeast(1)
        if (arriveAnchorAt > 0) return hops - 1
        return if (total <= 0) 0 else (((now - boardedAt).toDouble() / total) * hops).toInt().coerceIn(0, hops - 1)
    }

    private fun metroProgress(mr: ChelaileApi.MetroRoute, total: Long, now: Long): Pair<Int, Long> {
        val hops = mr.hops.coerceAtLeast(1)
        val planned = (total / hops).coerceAtLeast(60_000L)
        // 已经按定位对上过第 2 站以后：用实际走过的速度（上车到锚点平均每站多久）；只往慢里调，免得提前说快到了
        val observed = if (metroAnchorIdx >= 2 && metroAnchorAt > boardedAt) (metroAnchorAt - boardedAt) / metroAnchorIdx else 0L
        val perHop = maxOf(planned, observed.coerceAtMost(240_000L))
        // E1：有按基站学到的到站时刻就用它，已过几站按剩余时间倒推（区间长短不一，可能差一站）
        if (fpEtaAt > 0 && fpEtaAt > boardedAt) {
            val rem = (fpEtaAt - now).coerceAtLeast(0L)
            val full = fpEta.values.maxOrNull()?.takeIf { it > 0 }
            val st = mr.stations
            // 按真实站间距离分配剩余时间（区间长短不一，均分会慢一站）；10/30 早：已到第 2 站还显示第 1 站
            val fpPassed = if (full != null && st.size == hops + 1) {
                val cum = DoubleArray(st.size)
                for (i in 1 until st.size) cum[i] = cum[i - 1] + Geo.distanceM(st[i - 1].lat, st[i - 1].lng, st[i].lat, st[i].lng)
                val pos = (1.0 - rem.toDouble() / full).coerceIn(0.0, 1.0) * cum.last()
                cum.indexOfLast { it <= pos + 150.0 }
            } else hops - ((rem + planned - 1) / planned).toInt()
            // 定位对上过的站（metro_stop）也算：两者取大，不再被 E1 压住
            val anchorPassed = if (metroAnchorIdx >= 0 && metroAnchorAt > 0) metroAnchorIdx + ((now - metroAnchorAt) / perHop).toInt() else -1
            return maxOf(fpPassed, anchorPassed).coerceIn(0, hops - 1) to fpEtaAt
        }
        return if (metroAnchorIdx >= 0 && metroAnchorAt > 0) {
            val passed = (metroAnchorIdx + ((now - metroAnchorAt) / perHop).toInt()).coerceIn(0, hops - 1)
            passed to (metroAnchorAt + (hops - metroAnchorIdx) * perHop)
        } else {
            val passed = ((now - boardedAt).toDouble() / total * hops).toInt().coerceIn(0, hops - 1)
            passed to (boardedAt + total)
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
        // 地铁里网络定位常常漂几百米到几公里：只认精度 300 米内的，其余当没定位（按站数和时间估）
        val mrRide = if (metro) metroRoutes[legIdx] else null
        val locUse = if (metro) loc?.takeIf { it.accuracy <= 300 } else loc
        if (mrRide != null && locUse != null && now - locUse.time < 180_000 && locUse.time > boardedAt) {
            val k = mrRide.stations.indices.minByOrNull { Geo.distanceM(locUse.latitude, locUse.longitude, mrRide.stations[it].lat, mrRide.stations[it].lng) }
            if (k != null && k > metroAnchorIdx && k < mrRide.stations.size - 1 &&
                Geo.distanceM(locUse.latitude, locUse.longitude, mrRide.stations[k].lat, mrRide.stations[k].lng) <= 400) {
                metroAnchorIdx = k; metroAnchorAt = locUse.time
                TraceLog.event("metro_stop", "idx" to k, "left" to (mrRide.hops - k))
                saveRun(deadlineAt)
            }
        }
        val d = if (dest == null) null else locUse?.let { Geo.distanceM(it.latitude, it.longitude, dest.first, dest.second) }
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

        val timeEta = anchoredArrive(legIdx)
            ?: mrRide?.let { metroProgress(it, rideMs(legIdx).coerceAtLeast(60_000L), now).second }
            ?: (boardedAt + rideMs(legIdx))
        // 公交按沿线距离估过到站时间：取两者晚的，免得时间估短了提前结束
        val eta = if (!metro && busEtaAt > 0) maxOf(timeEta, busEtaAt) else timeEta
        // 公交站距常只有 300–500 米，700 米会提前两三站（10/29 晚实测）：公交收到 350 米；地铁仍 700 米
        val freshLoc = loc != null && now - loc.time < 120_000 && loc.accuracy < 200
        val near = d != null && (loc?.accuracy ?: 9999f) < 400 &&
            (if (metro) d <= 700 else d <= 350 + minOf(loc?.accuracy ?: 0f, 50f))
        // 公交有新鲜沿线投影：过了倒数第二站（只剩 1 站）且离站 ≤ 600 米才算快到
        val busLast = !metro && bs != null && bs.stopsLeft <= 1 && d != null && d <= 600
        // 地铁段还没拿到站数（服务刚恢复、预取没完成）时，手填的乘车时间不可靠，先不按时间提醒下车
        val etaKnown = !metro || metroRideSec[legIdx] != null || Learn.rideMin(this, trip.id, legIdx) != null
        // 按时间提醒「快到了」：有定位且离下车站 1.5 公里以上时不提醒（地铁里网络定位误差大，放宽精度）
        // 公交有新鲜定位时只信位置，不按倒计时（倒计时只在没定位时兜底）
        val timeUp = etaKnown && now >= eta - 90_000 && !freshFar &&
            (if (metro) (d == null || d < 1_500 + (loc?.accuracy ?: 0f)) else !freshLoc)
        if (arriveAlertedAt == 0L && (near || busLast || timeUp)) {
            TraceLog.event("arrive_alert", "d" to d?.toInt(), "near" to near, "busLast" to busLast, "timeUp" to timeUp)
            arriveAlertedAt = now
            LiveNotifier.alert(this, "快到 $name 了", "准备下车", metro = metro, id = 1005)
        }
        // 结束：到了下车站 150 米内；或提醒后过了 5 分钟且超过预计到站时间（地铁 3 分钟、公交 10 分钟）。
        // 新鲜定位显示离下车站 1.5 公里以上时绝不按时间结束（人还在车上，只是车慢）
        // 地铁：出站后拿到较准的定位（≤100 米）离下车站 500 米内，且已过预计到站 → 直接算到了，不等 150 米
        val metroOut = metro && loc != null && now - loc.time < 120_000 && loc.accuracy <= 100 && d != null && d <= 500 && now >= eta - 60_000L
        val byLoc = (arriveAlertedAt > 0 && d != null && d <= 150) || metroOut
        val byTime = arriveAlertedAt > 0 && !freshFar && now - arriveAlertedAt > 5 * 60_000L &&
            now > eta + (if (metro) 3 else 10) * 60_000L
        if (byLoc || byTime) {
            if (byLoc && br != null && !restored && boardedAt > 0) {
                val min = (now - boardedAt) / 60_000.0
                if (min >= 3) Learn.recordBusSpeed(this, trip.id, legIdx, BusRide.lengthM(br) / min)
            }
            TraceLog.event("ride_end", "by" to (if (byLoc) "loc" else "time"))
            // 最后一段是地铁、按位置到站：记下这次实际坐了多久（下次按站推算更准，不再提前说快到了）
            if (byLoc && !metroOut && metro && boardedAt > 0) {
                val min = ((now - boardedAt) / 60_000L).toInt()
                val plan = (rideMs(legIdx) / 60_000L).toInt()
                if (min >= 3 && min in plan / 2..plan * 2 + 5) learnRide(legIdx, min)
            }
            arrived = true
            endTrip("ride_" + (if (byLoc) "loc" else "time")); return 30_000L
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
            mrRide != null && arriveAlertedAt == 0L -> (metroProgress(mrRide, total, now).first * 100 / mrRide.hops.coerceAtLeast(1)).coerceIn(0, 97)
            d != null && span != null -> ((1 - d / span) * 100).toInt().coerceIn(0, 97)
            else -> ((now - boardedAt) * 100 / total).toInt().coerceIn(0, 100)
        }
        val left = if (metro) ((eta - now) / 60_000L).toInt().let { if (freshFar) it.coerceAtLeast(1) else it }
            else if (d != null && d > 1_500) (d / 350.0).toInt().coerceAtLeast(1)
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
                val passed = metroProgress(mr, total, now).first
                val next = mr.stations[passed + 1].name
                val n = mr.hops.coerceAtLeast(1) - passed
                "当前 ${mr.stations[passed].name} · 下一站 $next · 还有 $n 站到 $name"
            }
            bs != null && br != null ->
                if (bs.stopsLeft <= 1) "下一站就是 $name" else "下一站 ${br[bs.seg + 1].name} · 还有 ${bs.stopsLeft} 站到 $name"
            else -> "前往 $name" + (d?.let { " · 还有约 ${"%.1f".format(it / 1000)} 公里" } ?: "")
        }
        // 流体云标题：地铁有站点数据时直接显示「当前站 → 下一站」
        val metroCur = if (mr != null && arriveAlertedAt == 0L) metroProgress(mr, total, now).first else -1
        val title = if (metroCur >= 0) "$lineTitle · ${mr!!.stations[metroCur].name} → ${mr.stations[metroCur + 1].name}" else "$lineTitle · 到 $name"
        val v = LiveNotifier.Visual(title, text, short,
            if (trip.legs.size > 1) "${trip.name} · 最后一段" else trip.name,
            progress = p, stops = if (bs != null) hops.coerceIn(0, 12) else 0,
            metro = metro, trackerRes = if (metro) R.drawable.ic_metro else R.drawable.ic_bus, riding = true)
        LiveNotifier.update(this, LiveNotifier.build(this, v))
        // 界面用的站点进度：公交按沿线投影，地铁按已坐时间推算（与上面文字同一算法，只读）
        val stopUi: Pair<Int, Int>? = when {
            mr != null && arriveAlertedAt == 0L -> {
                val hopsM = mr.hops.coerceAtLeast(1)
                metroProgress(mr, total, now).first to hopsM
            }
            bs != null && hops > 0 -> bs.seg.coerceIn(0, hops) to hops
            else -> null
        }
        val names: Triple<String?, String?, String?> = when {
            mr != null && arriveAlertedAt == 0L -> Triple(mr.stations[metroCur.coerceAtLeast(0)].name, mr.stations.first().name, mr.stations.last().name)
            bs != null && br != null && hops > 0 -> Triple(br[bs.seg.coerceIn(0, hops)].name, br.first().name, br.last().name)
            else -> Triple(null, null, null)
        }
        LiveState.publish(this, LiveSnapshot(trip.id, trip.name, legIdx, trip.legs.size, lineTitle, short, text, null, p, null, riding = true,
            stopsPassed = stopUi?.first, stopsTotal = stopUi?.second,
            curStop = names.first, fromStop = names.second, toStop = names.third), deadlineAt)
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
                val passed = connectPassed(r, total, now)
                val next = r.stations[passed + 1].name
                val left = r.hops.coerceAtLeast(1) - passed
                val curName = r.stations[passed].name
                val line = prev.candidates.firstOrNull { it.isMetro() }?.label() ?: prev.title()
                return LiveNotifier.Visual("$line · $curName → $next", "当前 $curName · 下一站 $next · 还有 $left 站到 ${r.stations.last().name} · ${a.detail}",
                    a.headline, sub, countdownTo = arr?.let { now + it.etaSec * 1000L }, progress = p,
                    stops = r.hops.coerceAtMost(12), metro = true, trackerRes = R.drawable.ic_metro)
            }
            if (!prev.isMetroOnly() && now < expectArrive) {
                // 还在公交上：主角是这趟公交，到换乘站还要多久；下一段地铁只在小字里提一句
                val bus = prev.candidates.firstOrNull { !it.isMetro() }?.label() ?: prev.title()
                val at = prev.candidates.firstOrNull { it.alightName.isNotEmpty() }?.alightName ?: "换乘站"
                val left = ((expectArrive - now) / 60_000L).toInt().coerceAtLeast(1)
                val nextLeg = c?.let { "${it.label()} · ${it.stopName}" } ?: leg.title()
                val walk = if (leg.transferWalkMin > 0) " · 下车后步行约 ${leg.transferWalkMin} 分钟" else ""
                return LiveNotifier.Visual("$bus 乘车中 → $at", "约 $left 分钟到 $at$walk · 换乘 $nextLeg",
                    "约${left}分", sub, countdownTo = expectArrive, progress = p.coerceIn(0, 100), stops = 0,
                    metro = false, trackerRes = R.drawable.ic_bus)
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
        // 不设最小位移：人不动时也照样按间隔回调，否则在家里一直「定位中」
        val minDist = 0f
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
    private var lastSpeedFixT = 0L
    /** 最后一段公交是靠速度/位置自动判的上车（不是刷卡、不是手点）：之后几分钟内来了公交刷卡，以刷卡为准 */
    private var busBoardGuessed = false
    private var boardingByTap = false
    /** 基站确认是在这段的配置上车站进的站（不是半路上车） */
    private var originCellLeg = -1

    private fun speedOf(loc: Location): Float? {
        if (loc.hasSpeed()) return loc.speed
        val p = lastFixForSpeed
        if (loc.accuracy < 100) lastFixForSpeed = Location(loc)
        if (p == null || loc.accuracy >= 100) return null
        val dt = (loc.time - p.time) / 1000.0
        if (dt < 5 || dt > 120) return null
        return (Geo.distanceM(p.latitude, p.longitude, loc.latitude, loc.longitude) / dt).toFloat()
    }

    // ---------- 常去地点（只显示/记录，不参与判断） ----------
    private var placeStart: Location? = null   // 这趟第一段上车前的第一个好定位（起点）
    private var arrived = false                // 按到站结束（到下车站 / 刷卡出站）
    private var lastPlace: Places.Label? = null
    private var placeSince = 0L
    private var startLabel: Places.Label? = null

    private fun placeFix(loc: Location) {
        runCatching {
            Places.passive(this, loc.latitude, loc.longitude, loc.accuracy, loc.time)
            if (!::trip.isInitialized || loc.accuracy >= 200) return
            if (placeStart == null && legIdx == 0 && boardedAt == 0L && loc.time >= startedAt - 30_000) placeStart = Location(loc)
            val l = Places.classify(this, loc.latitude, loc.longitude, loc.accuracy)
            if (l != lastPlace) { lastPlace = l; placeSince = loc.time; TraceLog.event("place", "label" to l.name) }
            if (startLabel == null && !restored && loc.time - startedAt < 120_000L) startLabel = l
            // H3 兜底：进了家 / 公司（不是出发的那个地方）待满 3 分钟、行程还没结束 → 直接算到了
            if ((l == Places.Label.HOME || l == Places.Label.WORK) && l != startLabel && !arrived && !tail && !finishing &&
                loc.time - startedAt > 5 * 60_000L && loc.time - placeSince >= 3 * 60_000L) {
                TraceLog.event("place_arrive", "label" to l.name, "leg" to legIdx, "riding" to riding)
                Diag.mark(this, "已经${if (l == Places.Label.HOME) "到家" else "到公司"}，行程结束")
                arrived = true
                if (metroRideOn) runCatching { endMetroRide() }
                placeVotes(); finish()   // 人已经在目的地，不用再录「最后一段路」
            }
        }
    }

    /**
     * 结束时投起点票：真的坐过车才投（起点 = 出发前在家/公司里的第一个好定位）。
     * 终点不再按下车站投（下车后还要走一段，下车站不是家/公司），改由到站后的「驻留」来投，见 tailTick。
     */
    private var startVoted = false
    private fun placeVotes() {
        if (!::trip.isInitialized || startVoted) return
        startVoted = true
        runCatching {
            val boarded = boardedAt > 0 || riding || legIdx > 0
            placeStart?.takeIf { boarded }?.let { Places.voteTrip(this, true, startedAt, it.latitude, it.longitude, it.accuracy, it.time) }
        }
    }

    // ---------- 到站后的最后一段步行：只记录 + 学常去地点，不提醒、不上流体云 ----------
    private var tail = false
    private var tailStart = 0L
    private val tailFixes = ArrayDeque<Location>()
    private val TAIL_MS = 25 * 60_000L      // 最多记 25 分钟
    private val DWELL_MS = 5 * 60_000L      // 在同一处（60 米内）待满 5 分钟才算到了目的地
    private val DWELL_R = 60.0

    /** 到站结束：行程算完（不再恢复），但再低功耗记一会儿下车后的步行，人停下来或满 25 分钟就停 */
    private fun endTrip(by: String) {
        if (tail || finishing) return
        if (metroRideOn) runCatching { endMetroRide() }
        if (!arrived) { finish(); return }
        placeVotes()
        val now = System.currentTimeMillis()
        tail = true; tailStart = now; tailFixes.clear()
        clearRun()
        LiveState.clearPersisted(this)
        if (instance === this || instance == null) LiveState.snapshot.value = null
        LiveNotifier.replace(this, LiveNotifier.quiet(this, "${trip.name} 已到站", "正在记录到目的地的最后一段路（学习常去地点）"))
        TraceLog.event("tail_start", "by" to by)
        holdCpu(false)
        stepSensor(false)
        deadlineAt = now + TAIL_MS
        setPower(Power.LOW)
    }

    /**
     * 驻留判断：进入记录满 5 分钟后，最近 5 分钟的好定位（精度 ≤ 100 米）至少 3 个、跨度 ≥ 3 分钟、都在中心 60 米内，
     * 且最近 3 分钟走路不到 30 秒 → 人停下来了，结束记录（只留轨迹给分析用，不投票）。
     */
    private fun tailTick(now: Long): Long {
        val b = best
        if (b != null && b.time > tailStart && b.accuracy <= 100 && tailFixes.lastOrNull()?.time != b.time) {
            tailFixes.addLast(Location(b)); while (tailFixes.size > 60) tailFixes.removeFirst()
        }
        if (now - tailStart < DWELL_MS) return 30_000L
        val recent = tailFixes.filter { now - it.time <= DWELL_MS }
        if (recent.size < 3 || recent.last().time - recent.first().time < 3 * 60_000L) return 30_000L
        val wsum = recent.sumOf { 1.0 / it.accuracy.coerceAtLeast(10f) }
        val lat = recent.sumOf { it.latitude / it.accuracy.coerceAtLeast(10f) } / wsum
        val lng = recent.sumOf { it.longitude / it.accuracy.coerceAtLeast(10f) } / wsum
        val spread = recent.maxOf { Geo.distanceM(lat, lng, it.latitude, it.longitude) }
        if (spread > DWELL_R) return 30_000L
        val walking = TraceLog.recentWindows().count { it.t > now - 180_000L && AccMotion.classify(it) == AccMotion.Kind.WALK }
        if (walking > 15) return 30_000L
        val acc = recent.map { it.accuracy }.sorted()[recent.size / 2]
        // 停下来只用来结束记录，不再投终点票：家/公司改由全天随机采样来学（PlaceSampler）
        TraceLog.event("tail_dwell", "after" to (now - tailStart) / 1000, "spread" to spread.toInt(), "acc" to acc.toInt())
        runCatching { learnLastWalk(lat, lng) }
        finish()
        return 60_000L
    }

    private var lastLocAt = 0L

    private fun onLocation(loc: Location) {
        val nowL = System.currentTimeMillis()
        // 定位整段断掉（系统冻结后台）：记下断了多久，给保活排查用
        if (lastLocAt > 0 && nowL - lastLocAt > 3 * 60_000L) TraceLog.event("loc_gap", "sec" to (nowL - lastLocAt) / 1000)
        lastLocAt = nowL
        TraceLog.loc(loc)
        placeFix(loc)
        val spd = speedOf(loc)
        val cur = best
        val fresher = cur == null || loc.time - cur.time > 15_000
        if (fresher || loc.accuracy <= cur!!.accuracy) best = loc
        if (loc.provider == LocationManager.GPS_PROVIDER) lastGpsFix = loc
        // 第一段步行阶段（还没到上车点）：留下定位轨迹，给学步行用
        if (::trip.isInitialized && !tail && !firstWalkDone && legIdx == 0 && boardedAt == 0L) {
            walkFixes.addLast(WalkLearn.Fix(loc.time, loc.latitude, loc.longitude, loc.accuracy))
            while (walkFixes.size > 300) walkFixes.removeFirst()
        }
        if (!::trip.isInitialized || riding) return

        // 判上车用的速度只信 GPS（精度 ≤30 米、每个定位时刻只算一次）：网络定位常报假速度，出地铁口时尤其多
        val trusted = loc.provider == LocationManager.GPS_PROVIDER && loc.accuracy <= 30f && loc.time != lastSpeedFixT
        if (trusted) lastSpeedFixT = loc.time
        // 中途才打开：还没到过上车点、但速度像坐车且贴着这条线走 → 说明已经在车上了
        if (spd != null && trusted) {
            speeds.addLast(spd); if (speeds.size > 5) speeds.removeFirst()
            routeHits.addLast(onRoute(loc, trip.legs[legIdx])); if (routeHits.size > 5) routeHits.removeFirst()
            val onLine = routeHits.size >= 3 && routeHits.toList().takeLast(3).all { it }
            // 不再要求「没到过上车点 / 第一段」：换乘后（boardedAt 已有值）、没在站旁拿到定位的公交段同样适用
            if (!trip.legs[legIdx].isMetroOnly() &&
                (Motion.classify(speeds.toList(), onLine) == Motion.State.RIDE || busRideLikely())) {
                TraceLog.event("bus_board", "by" to "gps", "near" to (nearSince > 0), "spd" to speeds.toList().toString(), "onLine" to routeHits.count { it })
                speeds.clear(); routeHits.clear()
                onBoarded(manual = false); return
            }
        }

        if (spd != null && trusted && busJumpCheck(loc, spd)) return

        // 到过上车点、现在速度很快 → 大概率上车了
        // 地铁段不看车速：站口附近跑两步、打车到站口都会被当成「车开了」；地铁上车只认刷卡 / 地下基站 / 列车晃动
        if (nearSince > 0 && spd != null && trusted && spd > 6f && !trip.legs[legIdx].isMetroOnly()) {
            fastFixes++
            if (fastFixes >= 3) onBoarded(manual = false)
        } else if (spd != null && trusted && spd < 1f && fastFixes > 0) {
            fastFixes--   // 红灯/进站停一下不清零，真停下来才慢慢减
        }
        // 公交段：GPS 已经是车速却没判上车，记下卡在哪个条件上（每分钟最多一条）
        if (spd != null && trusted && spd > 6f && !trip.legs[legIdx].isMetroOnly() && !riding && loc.time - busFastLogged > 60_000L) {
            busFastLogged = loc.time
            TraceLog.event("bus_fast_wait", "spd" to spd, "near" to (nearSince > 0), "boardedAt" to boardedAt, "onLine" to routeHits.toList().takeLast(3).count { it },
                "route" to trip.legs[legIdx].candidates.count { routes[it.lineId] != null }, "fast" to fastFixes)
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
            .putInt("busSeg", busMaxSeg).putInt("joinLeg", joinLeg).putInt("joinSkip", joinSkip)
            .putInt("mAnchorIdx", metroAnchorIdx).putLong("mAnchorAt", metroAnchorAt)
            .putLong("arrAlert", arriveAlertedAt).putBoolean("trAlert", transferAlerted).putLong("fpEtaAt", fpEtaAt)
            .putLong("pendBoard", pendingBoardAt).putBoolean("boardDone", boardCommitted)
            .putString("pendLoc", pendingBoardLoc?.let { "${it.latitude},${it.longitude}" } ?: "").apply()
    }

    private fun clearRun() = runPrefs().edit().clear().apply()

    private fun savedRunTrip(): String? = runPrefs().let { p ->
        p.getString("trip", null)?.takeIf { p.getLong("deadline", 0) > System.currentTimeMillis() }
    }

    /** 有同一趟行程的未结束进度就恢复，返回原来的截止时间；没有返回 null */
    // 行程中保持 CPU 不睡：10/30 早熄屏后进程被冻结 4–5 分钟（传感器、基站、主循环全停），地铁里一点更新都没有
    private var wl: PowerManager.WakeLock? = null
    private fun holdCpu(on: Boolean) {
        runCatching {
            if (on) {
                if (wl == null) wl = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "busnotice:trip").apply { setReferenceCounted(false) }
                wl?.acquire(100 * 60_000L)   // 最多 100 分钟，防忘记释放
            } else wl?.takeIf { it.isHeld }?.release()
        }
    }

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
        p.getInt("joinLeg", -1).takeIf { it in t.legs.indices }?.let { pendingJoin = it to p.getInt("joinSkip", 0) }
        metroAnchorIdx = p.getInt("mAnchorIdx", -1); metroAnchorAt = p.getLong("mAnchorAt", 0L)
        // 提醒过的不再重复弹（10/30 早：被杀重启后又弹一次「准备下车」，人已经到公司了）
        arriveAlertedAt = p.getLong("arrAlert", 0L); transferAlerted = p.getBoolean("trAlert", false)
        fpEtaAt = p.getLong("fpEtaAt", 0L)
        busMaxSeg = p.getInt("busSeg", -1); busSeg = busMaxSeg   // 站点列表不存，恢复后重新取
        restored = true
        val rc = p.getString("ridingCand", "") ?: ""
        ridingCand = t.legs[leg].candidates.firstOrNull { "${it.name}|${it.stopName}" == rc }
            ?: if (riding) t.legs[leg].candidates.firstOrNull() else null
        Diag.mark(this, "已恢复中断前的行程：第 ${leg + 1} 段${if (riding) "（车上）" else ""}")
        return dl
    }

    private var finishing = false
    private var trustFixSince = 0L

    /** 本服务正在跑的行程：主循环还活着才算 */
    fun runningId(): String? = if (::trip.isInitialized && !finishing && !tail && loop?.isActive == true) trip.id else null

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
    // 刷卡时位置对不上（比如刷了卡但定位离这段站点很远）：先记下来，之后定位对上了再按刷卡时刻补上
    private var deferredTapAt = 0L
    // 坐完地铁后有没有见过出站刷卡：地铁出站扣费也弹「消费成功」，第一次刷卡一律算出站
    private var metroExitSeen = false
    private var metroExitAt = 0L
    private var deferredTapKind: TransitSignal.Kind? = null
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

    // ---------- 半路上车：从地铁段沿线后面某一站进站 ----------
    private var joinLeg = -1          // 改过上车站的那段；-1 = 按配置的上车站
    private var joinSkip = 0          // 跳过了前面几站
    private var joinScale = 1.0       // 剩下的站数 / 全程站数
    private var joinOrigStop = ""     // 原来配置的上车站（查首末班用）
    private var joinStop = ""
    private var joinCandIdx = -1      // 定位第一次贴近的沿线站（要停留确认）
    private var joinCandAt = 0L
    private var joinCandFixT = 0L
    private var pendingJoin: Pair<Int, Int>? = null   // 被杀重启后，等途经站查到再恢复

    private fun dirKey(c: Candidate) =
        "${if (joinOrigStop.isNotEmpty() && c.stopName == joinStop) joinOrigStop else c.stopName}|${c.name}"

    private fun learnRide(i: Int, minutes: Int) {
        if (i == joinLeg) return   // 只坐了一部分，不学这段乘车时长
        Learn.recordRide(this, trip.id, i, minutes)
    }

    /**
     * 第 i 段地铁还没上车时，人离配置的上车站 600 米以上、却在沿线后面某站 300 米内：
     * 定位要隔 45 秒两次都贴着同一站才认（路过不算）；刷卡进站时位置对上直接认。
     */
    private fun tryJoin(loc: Location, i: Int, now: Long, byTap: Boolean, coarse: Boolean = false): Boolean {
        if (joinLeg >= 0 || i !in trip.legs.indices || i < legIdx) return false
        // 基站已确认在配置的上车站进站：之后人在沿线别的站附近只是车开过去了，不是半路上车
        if (originCellLeg == i && !byTap) return false
        val leg = trip.legs[i]
        if (!leg.isMetroOnly() || (i == legIdx && (riding || stationByTap))) return false
        if (i == legIdx && i > 0 && boardedAt > 0 && alightAt == 0L) return false   // 还在上一段车上
        val r = metroRoutes[i] ?: return false
        if (r.stations.size < 3) return false
        val c = leg.candidates.firstOrNull { it.isMetro() } ?: return false
        val slack = loc.accuracy.coerceAtMost(if (coarse) 500f else 150f)
        val fromOrigin = Geo.distanceM(loc.latitude, loc.longitude, c.lat, c.lng)
        if (fromOrigin < 600 || (coarse && fromOrigin - loc.accuracy < 500)) return false
        val j = JoinPick.pick(r.stations.map { it.lat to it.lng }, loc.latitude, loc.longitude, 300.0 + slack) ?: run { joinCandIdx = -1; return false }
        if (!byTap) {
            // 粗定位：两次不同时刻的定位（隔 20 秒以上）指向同一站；准定位：贴着同一站 45 秒
            if (joinCandIdx != j) { joinCandIdx = j; joinCandAt = now; joinCandFixT = loc.time; return false }
            if (coarse) { if (loc.time - joinCandFixT < 20_000L) return false }
            else if (now - joinCandAt < 45_000L) return false
        }
        applyJoin(i, j)
        return true
    }

    private fun applyJoin(i: Int, j: Int) {
        val r = metroRoutes[i] ?: return
        if (j < 1 || j >= r.stations.size - 1) return
        val st = r.stations[j]
        val leg = trip.legs[i]
        joinScale = (r.hops - j).toDouble() / r.hops
        joinOrigStop = leg.candidates.firstOrNull { it.isMetro() }?.stopName ?: ""
        joinStop = st.name
        joinLeg = i; joinSkip = j
        val legs = trip.legs.toMutableList()
        legs[i] = leg.copy(
            candidates = leg.candidates.map { if (it.isMetro()) it.copy(stopName = st.name, lat = st.lat, lng = st.lng) else it },
            rideMin = (leg.rideMin * joinScale).toInt().coerceAtLeast(1),
        )
        trip = trip.copy(legs = legs)
        val sub = ChelaileApi.MetroRoute(r.stations.drop(j), (r.durSec * joinScale).toInt())
        metroRoutes[i] = sub; metroRideSec[i] = sub.durSec
        if (i == 0) firstWalkDone = true   // 不是从平常的地方出发，首段步行不学
        nearSince = 0L
        TraceLog.event("join", "leg" to i, "skip" to j, "left" to sub.hops)
        Diag.mark(this, "从沿线 ${st.name} 上车，按剩下 ${sub.hops} 站算")
        saveRun(deadlineAt)
        kick()
    }

    /** 当前公交段有车正在进站 / 停在站上（刷卡时刻前后 90 秒） */
    private fun busAtStopNow(at: Long): Boolean =
        trip.legs[legIdx].candidates.any { BusSampler.busAtStop(it, at) }

    /**
     * 通知栏里「N 分钟前」的刷卡（当时没读到）：坐完地铁、还没认出出站、也还没上公交时，
     * 那一下就是出站；当时公交站正好有车的话按上公交算。其余情况不补，免得重复。
     */
    fun onStaleTap(at: Long) {
        scope.launch(Dispatchers.Main) {
            runCatching {
                if (!::trip.isInitialized || tail || riding) return@runCatching
                // 公交段还没上车：刷卡弹窗当时没读到、1–2 分钟后才在通知栏看到 → 按刷卡时刻补认上车（照样要位置对得上）
                val cur = trip.legs[legIdx]
                if (!cur.isMetroOnly() && (legIdx == 0 || !trip.legs[legIdx - 1].isMetroOnly()) && System.currentTimeMillis() - at <= 3 * 60_000L) {
                    TraceLog.event("tap_backfill", "as" to "BUS_BOARD", "leg" to legIdx, "at" to at)
                    applyTransit(TransitSignal.Kind.TAP, "补读刷卡", at); return@runCatching
                }
                if (metroExitSeen || legIdx == 0) return@runCatching
                if (!trip.legs[legIdx - 1].isMetroOnly() || trip.legs[legIdx].isMetroOnly()) return@runCatching
                if (boardedAt <= 0 || at - boardedAt < 3 * 60_000L) return@runCatching
                val bus = busAtStopNow(at)
                TraceLog.event("tap_backfill", "as" to if (bus) "BUS_BOARD" else "EXIT", "leg" to legIdx, "at" to at)
                applyTransit(if (bus) TransitSignal.Kind.TAP else TransitSignal.Kind.EXIT, "补读刷卡", at)
            }.onFailure { Log.w(TAG, "补读刷卡失败", it) }
        }
    }

    private fun kick() {
        if (finishing || deadlineAt <= System.currentTimeMillis()) return
        loop?.cancel(); loop = scope.launch { runLoop(deadlineAt) }
    }

    /** 刷卡时刻前后 2 分钟内、精度 200 米内的定位；没有就返回 null（地下、刚启动），这时退回按段顺序判断 */
    private fun tapFix(at: Long): Location? =
        best?.takeIf { it.accuracy < 200 && kotlin.math.abs(at - it.time) < 120_000 && System.currentTimeMillis() - it.time < 180_000 }

    /** 定位是否在第 i 段公交的上车站附近（300 米）或贴着这条线（150 米） */
    private fun nearBusLeg(loc: Location, i: Int): Boolean {
        val leg = trip.legs[i]
        val slack = loc.accuracy.coerceAtMost(100f)
        if (leg.candidates.any { !it.lat.isNaN() && Geo.distanceM(loc.latitude, loc.longitude, it.lat, it.lng) <= 300 + slack }) return true
        val pts = leg.candidates.filter { !it.isMetro() }.mapNotNull { routes[it.lineId] }.flatten()
        return pts.any { Geo.distanceM(loc.latitude, loc.longitude, it.first, it.second) <= 150 + slack }
    }

    /** 定位是否在第 i 段地铁的进站点 500 米内 */
    private fun nearMetroLeg(loc: Location, i: Int): Boolean {
        val slack = loc.accuracy.coerceAtMost(150f)
        return trip.legs[i].candidates.any { !it.lat.isNaN() && Geo.distanceM(loc.latitude, loc.longitude, it.lat, it.lng) <= 500 + slack }
    }

    private fun deferTap(kind: TransitSignal.Kind, at: Long, why: String) {
        deferredTapAt = at; deferredTapKind = kind
        TraceLog.event("tap_deferred", "kind" to kind.name, "leg" to legIdx, "why" to why)
        Diag.mark(this, "刷卡但位置对不上（$why），先不切换，等定位确认")
    }

    /** 每轮刷新：之前位置对不上的刷卡，5 分钟内定位对上了就按刷卡时刻补上；超时作废 */
    private fun retryDeferredTap(now: Long) {
        val k = deferredTapKind ?: return
        if (now - deferredTapAt > 5 * 60_000L || riding) { deferredTapKind = null; deferredTapAt = 0L; return }
        val loc = best?.takeIf { it.accuracy < 200 && now - it.time < 60_000 } ?: return
        val cur = trip.legs[legIdx]
        val metroTarget = if (cur.isMetroOnly()) legIdx else (legIdx + 1 until trip.legs.size).firstOrNull { trip.legs[it].isMetroOnly() }
        val ok = when {
            k == TransitSignal.Kind.ENTER -> metroTarget != null && nearMetroLeg(loc, metroTarget)
            cur.isMetroOnly() -> nearMetroLeg(loc, legIdx)
            else -> nearBusLeg(loc, legIdx)
        }
        if (!ok) return
        val at = deferredTapAt
        deferredTapKind = null; deferredTapAt = 0L
        TraceLog.event("tap_confirmed", "kind" to k.name, "leg" to legIdx)
        applyTransit(k, "补认刷卡", at, geoChecked = true)
    }

    private fun applyTransit(kind: TransitSignal.Kind, text: String, at: Long, geoChecked: Boolean = false) {
        if (!::trip.isInitialized || tail) return
        val last = trip.legs.size - 1
        when (kind) {
            TransitSignal.Kind.ENTER -> {
                if (riding) return
                // 已经按刷卡进过站：同一次进站的重复通知不算；之前只是定位推断的，用刷卡时刻覆盖
                if (trip.legs[legIdx].isMetroOnly() && stationByTap && stationAt > 0 && at - stationAt < 10 * 60_000L) return
                // 地理围栏：刷卡时有可靠定位、且离要切到的那段地铁站很远 → 先不认，等定位确认
                val target = if (trip.legs[legIdx].isMetroOnly()) legIdx else (legIdx + 1..last).firstOrNull { trip.legs[it].isMetroOnly() } ?: return
                val fix = if (geoChecked) null else tapFix(at)
                if (fix != null && !nearMetroLeg(fix, target) && !tryJoin(fix, target, at, byTap = true)) { deferTap(TransitSignal.Kind.ENTER, at, "离地铁站远"); return }
                if (!trip.legs[legIdx].isMetroOnly()) {
                    val k = target
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
                if (prevMetro || cur.isMetroOnly()) { metroExitSeen = true; metroExitAt = at }
                when {
                    // 最后一段地铁：出站 = 到了
                    cur.isMetroOnly() && legIdx == last && (riding || stationAt > 0) -> {
                        if (riding && !restored && boardedAt > 0) learnRide(legIdx, ((at - boardedAt) / 60_000L).toInt())
                        Diag.mark(this, "刷卡出站，行程结束")
                        arrived = true
                        endTrip("tap_exit")
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
            TransitSignal.Kind.TAP -> {
                // 余额通知只说明「刚刷了卡」：按现在在哪一段来解释成进站 / 出站 / 上公交
                val cur = trip.legs[legIdx]
                val prevMetro = legIdx > 0 && trip.legs[legIdx - 1].isMetroOnly()
                val sinceExit = at - metroExitAt
                val meaning = when {
                    // 坐完地铁后的第一次刷卡 = 出站扣费（哪怕定位/基站已经先判了下车），不是上公交
                    // 例外：基站已经判了出站、又过了 2 分钟以上才刷卡 → 出站那下没读到，这次是上公交
                    // 例外 2：这时候正好有车在公交站进站 / 停着（实时数据），或者判下车已经 8 分钟以上 → 是上公交
                    prevMetro && boardedAt > 0 && !metroExitSeen && at - boardedAt > 3 * 60_000L &&
                        !(cellExitAt > 0 && at - cellExitAt > 2 * 60_000L) &&
                        !(alightAt > 0 && (busAtStopNow(at) || at - alightAt > 8 * 60_000L)) -> TransitSignal.Kind.EXIT
                    // 还在地铁上（换乘中没下车，或最后一段坐着）且已坐了 3 分钟以上：出站
                    prevMetro && alightAt == 0L && boardedAt > 0 && at - boardedAt > 3 * 60_000L -> TransitSignal.Kind.EXIT
                    // 中间一段地铁：以为还在站台等车，但进站已经 3 分钟以上又刷卡 → 只能是出站
                    // 只有刷卡进过站才算：进站只是推断出来的（定位 / 时间），这次刷卡多半就是过闸进站
                    cur.isMetroOnly() && !riding && legIdx < last && stationAt > 0 && stationByTap && at - stationAt > 3 * 60_000L -> TransitSignal.Kind.EXIT
                    // 刚出站 1 分钟内又来一次：同一次出站的重复，不算上公交
                    prevMetro && metroExitSeen && sinceExit < 60_000L -> return
                    riding && cur.isMetroOnly() && at - boardedAt > 3 * 60_000L -> TransitSignal.Kind.EXIT
                    // 当前段是地铁、没在车上：进站（之前的进站刷卡 10 分钟内的重复不算）
                    cur.isMetroOnly() && !riding && !(stationByTap && stationAt > 0 && at - stationAt < 10 * 60_000L) -> TransitSignal.Kind.ENTER
                    // 当前段是公交、还没上：刷卡 = 上车（交通卡只在乘车时刷，不再要求先走到站牌边）
                    !cur.isMetroOnly() && !riding -> null
                    // 自动判的上公交 5 分钟内来了刷卡：之前判早了，上车时刻改成刷卡这一刻
                    !cur.isMetroOnly() && riding && busBoardGuessed && at >= boardedAt && at - boardedAt < 5 * 60_000L -> {
                        TraceLog.event("tap_reanchor", "leg" to legIdx, "was" to boardedAt, "at" to at)
                        Diag.mark(this, "刷卡确认上公交，上车时刻按刷卡校正")
                        busBoardGuessed = false; boardedAt = at; resetBusRide(); kick(); return
                    }
                    else -> return
                }
                TraceLog.event("tap", "as" to (meaning?.name ?: "BUS_BOARD"), "leg" to legIdx)
                if (meaning != null) { applyTransit(meaning, text, at, geoChecked); return }
                // 当成上公交之前看位置：在这段公交站/沿线附近才算；人其实在后面某段地铁站口 → 按进站算；都对不上先记下等确认
                val fix = if (geoChecked) null else tapFix(at)
                // 刚出地铁：换乘站离公交站往往很近，只认公交站 150 米内（不认沿线）
                val busOk = { l: Location -> if (prevMetro) trip.legs[legIdx].candidates.any { !it.lat.isNaN() &&
                    Geo.distanceM(l.latitude, l.longitude, it.lat, it.lng) <= 150 + l.accuracy.coerceAtMost(50f) } else nearBusLeg(l, legIdx) }
                if (fix != null && !busOk(fix)) {
                    val m = (legIdx + 1..last).firstOrNull { trip.legs[it].isMetroOnly() && nearMetroLeg(fix, it) }
                    if (m != null) { applyTransit(TransitSignal.Kind.ENTER, text, at, geoChecked = true); return }
                    deferTap(TransitSignal.Kind.TAP, at, "离公交站远"); return
                }
                Diag.mark(this, "刷卡上公交")
                boardingByTap = true
                try { onBoarded(manual = false, at = at) } finally { boardingByTap = false }
                kick()
            }
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

    // ---------- 学步行（只学不用）：出发地 → 第一段上车点、最后下车 → 目的地 ----------
    private val walkFixes = ArrayDeque<WalkLearn.Fix>()
    private var firstWalkDone = false
    private var userRestarted = false

    /** 第一段：走到上车站附近（公交）或进了站（地铁）的那一刻，结算这段步行 */
    private fun checkFirstWalk() {
        if (firstWalkDone || restored || legIdx != 0) return
        if (nearSince == 0L && stationAt == 0L) return
        firstWalkDone = true
        val end = if (stationAt > 0) stationAt else nearSince
        val wins = TraceLog.recentWindows()
        val start = WalkLearn.walkStart(wins, startedAt - 30_000L, end) ?: return
        // 启动时人已经在走（离开出发地才启动、手动重新开始）：时长只是下限，步速照样能用
        val partial = start - startedAt < 30_000L || userRestarted
        val s = WalkLearn.build(LocalDate.now().toEpochDay(), "first", start, end, walkFixes.toList(), wins, partial) ?: return
        WalkLearn.record(this, trip.id, s)
        TraceLog.event("walk_learn", "kind" to "first", "dur" to s.durSec, "path" to s.pathM, "walk" to s.walkSec, "spd" to s.speed, "partial" to partial)
    }

    /** 最后一段：从下车 / 出站（尾段记录开始）到在终点停下来的那一刻 */
    private fun learnLastWalk(lat: Double, lng: Double) {
        if (restored) return
        val fixes = tailFixes.map { WalkLearn.Fix(it.time, it.latitude, it.longitude, it.accuracy) }
        val arrive = WalkLearn.arriveAt(fixes, lat, lng) ?: return
        val wins = TraceLog.recentWindows()
        val s = WalkLearn.build(LocalDate.now().toEpochDay(), "last", tailStart, arrive, fixes, wins, partial = false) ?: return
        WalkLearn.record(this, trip.id, s)
        TraceLog.event("walk_learn", "kind" to "last", "dur" to s.durSec, "path" to s.pathM, "walk" to s.walkSec, "spd" to s.speed)
    }

    // ---------- 地铁基站：学地下专网区号判进出站，顺便记沿途基站序列（只存本机） ----------
    private var tacLearner: MetroTacLearner? = null
    private val metroGate = MetroGate { tac -> tacLearner?.isMetroTac(tac) == true }
    private val fpRec = MetroFpRecorder()
    private var fpEta: Map<Long, Long> = emptyMap()
    private val fpSeen = HashSet<Long>()
    /** 各段地铁的 E1 模型（后台读一次；读历史记录要一两秒，不放主线程） */
    private val etaCache = java.util.concurrent.ConcurrentHashMap<String, Map<Long, Long>>()
    private val etaLoading = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun etaFor(legI: Int): Map<Long, Long>? {
        val key = trip.id.take(8) + ":" + legI
        etaCache[key]?.let { return it }
        if (etaLoading.add(key)) {
            val single = trip.legs.count { it.isMetroOnly() } == 1
            val learner = tacLearner
            scope.launch(Dispatchers.IO) {
                val m = MetroCells.eta(applicationContext, key, if (single && learner != null) ({ t: Int? -> learner.isMetroTac(t) }) else null)
                etaCache[key] = m
                withContext(Dispatchers.Main) { TraceLog.event("fp_eta_load", "key" to key, "cells" to m.size) }
            }
        }
        return null
    }

    /**
     * G5：没在车上（中途才打开 / 重启 / 判漏了上车），却在地下见到一个学过的、不是上车站的基站
     * → 其实已经在车上了：直接切到这段地铁「车上」，上车时刻和到站时刻都按学到的剩余时间倒推。
     */
    private fun joinByCell(s: CellSnap, underground: Boolean): Boolean {
        if (!underground || riding && trip.legs[legIdx].isMetroOnly()) return false
        val e = s.enb?.toLong() ?: return false
        val m = listOf(legIdx, legIdx + 1).firstOrNull { it < trip.legs.size && trip.legs[it].isMetroOnly() } ?: return false
        val model = etaFor(m) ?: return false
        val rem = model[e] ?: return false
        val maxRem = model.values.maxOrNull() ?: return false
        if (maxRem - rem < 150_000L) return false   // 还在上车站附近：照常等刷卡 / 列车开动
        val boardAt = s.t - (maxRem - rem)
        TraceLog.event("metro_join_cell", "leg" to m, "enb" to e, "remS" to rem / 1000, "boardAt" to boardAt)
        Diag.mark(this, "认出地铁沿途的基站，判断已在车上")
        var n = 0
        while (!inMetroRide() && legIdx <= m && n++ < 4) onBoarded(manual = false, at = boardAt)
        if (!inMetroRide()) return false
        boardedAt = boardAt
        fpEta = model; fpEtaAt = s.t + rem
        saveRun(deadlineAt); kick()
        return true
    }
    /** E1：按基站学到的预计到下车站时刻（0 = 没有） */
    private var fpEtaAt = 0L
    private var metroRideOn = false
    private var metroRideKey = ""
    private var metroRideStart = 0L
    private var busMovingOn = false
    private var cellExitAt = 0L          // 基站判断出站的时刻（离开地下区号的第一次采样）

    /** 正在坐地铁（含站台等车）：最后一段地铁上车了，或者前一段是地铁、上了车还没下 */
    private fun inMetroRide(): Boolean {
        if (riding) return trip.legs[legIdx].isMetroOnly()
        return legIdx > 0 && trip.legs[legIdx - 1].isMetroOnly() && boardedAt > 0 && alightAt == 0L && !metroExitSeen
    }

    /** 坐着公交、车在路上跑（GPS 速度 ≥ 4 m/s）：这时见到的区号肯定是地面的 */
    private fun busMoving(now: Long): Boolean {
        val onBus = if (riding) !trip.legs[legIdx].isMetroOnly()
            else legIdx > 0 && !trip.legs[legIdx - 1].isMetroOnly() && boardedAt > 0 && alightAt == 0L
        val fast = best?.let { now - it.time < 30_000 && it.hasSpeed() && it.speed >= 4f } == true
        return onBus && fast
    }

    private fun onCell(s: CellSnap) {
        if (!::trip.isInitialized || tail || finishing) return
        val l = tacLearner ?: MetroCells.load(this).also { tacLearner = it }
        val underground = l.isMetroTac(s.nrTac)
        if (!inMetroRide()) joinByCell(s, underground)
        val inRide = inMetroRide()
        if (inRide) {
            val legM = if (riding) legIdx else legIdx - 1
            if (!metroRideOn) {
                metroRideOn = true; metroRideStart = s.t
                metroRideKey = trip.id.take(8) + ":" + legM
                fpSeen.clear()
            }
            if (fpEta.isEmpty()) etaFor(legM)?.let { fpEta = it }
            l.sampleRide(s); fpRec.sample(s, underground)
            // E1：地下见到一个新基站、学过它离下车站还有多久 → 用它校准到站时刻
            val e = s.enb?.toLong()
            val maxRem = fpEta.values.maxOrNull() ?: 0L
            // 上车站的基站（含站台等车）等多久不定，不拿来校准；中途重启时第一个基站已在路上，照样用
            if (underground && e != null && fpSeen.add(e)) fpEta[e]?.takeIf { maxRem - it >= 120_000L }?.let { rem ->
                fpEtaAt = s.t + rem
                TraceLog.event("fp_eta", "enb" to e, "remS" to rem / 1000, "eta" to fpEtaAt)
            }
        } else if (metroRideOn) endMetroRide()
        if (busMoving(s.t)) { busMovingOn = true; l.sampleSurface(s) }
        else if (busMovingOn) { busMovingOn = false; l.endSurface(); MetroCells.save(this, l) }

        val edge = metroGate.feed(s) ?: return
        val last = trip.legs.size - 1
        val cur = trip.legs[legIdx]
        when (edge.first) {
            MetroGate.Edge.ENTER -> {
                // 当前该进地铁站、还没判进站：连上地下区号 = 进站（刷卡来了会用刷卡时刻覆盖）
                if (!cur.isMetroOnly() || riding || stationAt > 0) return
                // 有可靠定位且离这段地铁站很远（比如坐公交路过别的地铁站）：不算
                val fix = best?.takeIf { it.accuracy < 200 && s.t - it.time < 60_000 }
                if (fix != null && !nearMetroLeg(fix, legIdx)) { TraceLog.event("cell_enter_skip", "leg" to legIdx); return }
                stationAt = edge.second; stationByTap = false
                val origin = cur.candidates.firstOrNull { it.isMetro() }
                if (fix != null && origin != null && Geo.distanceM(fix.latitude, fix.longitude, origin.lat, origin.lng) < 600 + fix.accuracy.coerceAtMost(200f))
                    originCellLeg = legIdx
                TraceLog.event("cell_enter", "leg" to legIdx, "origin" to (originCellLeg == legIdx))
                Diag.mark(this, "连上地铁里的基站，判断已进站")
                saveRun(deadlineAt); kick()
            }
            MetroGate.Edge.EXIT -> {
                if (!inRide) return
                cellExitAt = edge.second
                TraceLog.event("cell_exit", "leg" to legIdx, "at" to edge.second)
                when {
                    riding && cur.isMetroOnly() && legIdx == last -> {
                        endMetroRide()
                        Diag.mark(this, "离开地铁里的基站，判断已出站，行程结束")
                        arrived = true
                        endTrip("cell_exit")
                        return
                    }
                    !riding && legIdx > 0 && trip.legs[legIdx - 1].isMetroOnly() && alightAt == 0L -> {
                        endMetroRide()
                        markAlight(edge.second, by = "cell")
                    }
                }
                kick()
            }
        }
    }

    /** 一趟地铁结束：结算区号学习，存下沿途基站序列 */
    private fun endMetroRide() {
        metroRideOn = false
        val l = tacLearner ?: return
        val tac = l.endRide()
        MetroCells.save(this, l)
        fpEtaAt = 0L; fpSeen.clear(); fpEta = emptyMap()
        // 半路上车的也存：E1 只看到终点还剩多久，从哪站上都一样
        fpRec.finish()?.let { seq ->
            MetroCells.appendRide(this, metroRideKey, org.json.JSONObject()
                .put("start", metroRideStart).put("end", System.currentTimeMillis())
                .put("board", boardedAt).put("tacOk", tac != null).put("mt", true).put("join", joinLeg >= 0).put("seq", seq))
        }
        TraceLog.event("metro_cells", "tacOk" to (tac != null), "learned" to l.hasLearned(), "rides" to MetroCells.rideCount(this))
    }

    /** 换乘段：出站时刻就是下地铁时刻，顺便学这段地铁实际坐了多久 */
    private fun markAlight(at: Long, by: String = "tap") {
        if (alightAt > 0) return
        alightAt = at; stepSensor(false)
        TraceLog.event("alight_detect", "leg" to legIdx - 1, "by" to by, "at" to at)
        if (!rideLearned && !restored && boardedAt > 0) {
            rideLearned = true
            learnRide(legIdx - 1, ((at - boardedAt) / 60_000L).toInt())
        }
        Diag.mark(this, if (by == "tap") "刷卡出站，开始换乘" else "已到换乘点，开始换乘")
        saveRun(deadlineAt)
    }

    /** 用户点「结束」：这是真实到站/放弃的时刻，记录下来作为对照 */
    fun userFinish() {
        TraceLog.event("user_end", "leg" to legIdx, "riding" to riding)
        scope.launch(Dispatchers.Main) { loop?.cancel(); finish() }
    }

    private fun finish() {
        holdCpu(false)
        if (metroRideOn) runCatching { endMetroRide() }
        if (busMovingOn) runCatching { busMovingOn = false; tacLearner?.let { it.endSurface(); MetroCells.save(this, it) } }
        TraceLog.onCell = null
        if (!finishing && !tail) placeVotes()
        if (tail && !finishing) TraceLog.event("tail_end", "after" to (System.currentTimeMillis() - tailStart) / 1000)
        TraceLog.event("finish", "leg" to legIdx, "riding" to riding)
        TraceLog.stop()
        finishing = true
        clearRun()
        try { lm?.removeUpdates(listener) } catch (_: Exception) { }
        stepSensor(false)
        power = null; highSince = 0L; coolUntil = 0L
        LiveState.clearPersisted(this)
        if (instance === this || instance == null) LiveState.snapshot.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Diag.mark(this, "App 从最近任务里被划掉（服务应继续）")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        holdCpu(false)
        if (!finishing) { Diag.mark(this, "服务被系统停止（非主动结束）"); TraceLog.stop() }
        try { lm?.removeUpdates(listener) } catch (_: Exception) { }
        // 只有自己还是当前实例（或没有更新的实例）时才清内存里的看板，免得旧实例的 onDestroy 把新实例刚设的状态清掉。
        // 不是主动结束（被系统停掉、之后会恢复）：本地存的那份留着，重开 App 时还能显示
        val mine = instance === this || instance == null
        if (instance === this) { instance = null; TraceLog.onCell = null }
        if (mine) LiveState.snapshot.value = null
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
        // 演示回放中：「关闭」结束回放，其他按钮不做事（不能去碰真实行程）
        if (svc == null && DemoPlayer.running) {
            if (intent.action == ACTION_STOP) DemoPlayer.stop(ctx)
            return
        }
        when {
            intent.action == ACTION_BOARDED && svc != null -> svc.onBoarded()
            intent.action == ACTION_DISMISSED -> {
                // 被划掉（或被系统移除）：行程照常进行，胶囊先隐藏，等下一次提醒或打开 App 再出来
                Diag.mark(ctx, "胶囊被移除（划掉或系统移除），行程继续在后台")
                if (svc != null) LiveNotifier.hidden = true
            }
            intent.action == DepartAdvisor.ACTION_MUTE -> DepartAdvisor.mute(ctx)
            intent.action == ACTION_STOP -> CommuteService.stop(ctx)
            else -> CommuteService.stop(ctx)
        }
    }
}
