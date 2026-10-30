package com.busnotice.commute

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * 演示回放（录屏做宣传片用）。
 *
 * 一趟压缩过的回家行程：地铁 5 站 → 换乘站内等 2 分钟 → 现在走 → 公交 5 站 → 到家，
 * 提醒文案和判断逻辑同真实行程，30× 匀速约 1 分钟。线路名、站名全部是虚构的。
 * 不读任何真实数据、不联网、不写行程记录；正在跑真实行程时不能启动。
 */
object DemoPlayer {
    const val TRIP_ID = "demo-replay"
    const val BADGE = "演示回放 · 站点均为虚构"

    private const val METRO = "星河线"
    private const val BUS = "K7路"
    private val METRO_STOPS = listOf("云杉路", "望江", "松柏里", "文昌宫", "白鹭洲", "枫林")
    private val BUS_STOPS = listOf("枫林站东", "槐荫路", "明德桥", "观澜", "晓月湖", "青石桥")

    // 演示时间线（压缩过的一趟：地铁 5 站、公交 5 站、换乘站只等 2 分钟），提醒文案和判断逻辑同真实行程
    private fun s(h: Int, m: Int, sec: Int = 0) = h * 3600 + m * 60 + sec
    private val PRE = s(12, 5)            // 出发前：按换乘公交倒推，3 分钟后走
    private val T0 = s(12, 8)             // 现在走
    private val IN = s(12, 11)            // 已进站
    private val RIDE = s(12, 12)          // 列车进站、上车
    private val XFER = s(12, 19, 30)      // 到换乘站（5 站 × 1.5 分钟）
    private val WALK = 3                  // 换乘步行分钟
    private val BUS_ARR = XFER + 6 * 60   // 出站时公交还有 6 分钟 → 站内等 2 分钟再走
    private val TAP = BUS_ARR + 30        // 刷卡上公交
    private val ALIGHT = TAP + 5 * 90     // 公交 5 站 × 1.5 分钟
    private val PROMPT = ALIGHT - 100     // 提醒准备下车
    private val HOME = ALIGHT + 40        // 到站后停一下再显示「到家了」
    private val END = HOME + 20
    val trip = Trip(
        id = TRIP_ID, name = "回家", startMinute = 12 * 60, endMinute = 13 * 60 + 20,
        legs = listOf(
            Leg(listOf(Candidate(Candidate.Type.METRO, METRO, METRO_STOPS.first(), 0.0, 0.0, alightName = METRO_STOPS.last())), rideMin = 8),
            Leg(listOf(Candidate(Candidate.Type.BUS, BUS, BUS_STOPS.first(), 0.0, 0.0, alightName = BUS_STOPS.last())), rideMin = 8, transferWalkMin = WALK),
        ),
    )

    // 首页在演示期间显示的行程卡：全是虚构的，不露真实线路和时间
    val showTrips = listOf(
        Trip(
            id = "demo-out", name = "出门",
            legs = listOf(
                Leg(listOf(Candidate(Candidate.Type.BUS, BUS, BUS_STOPS.last(), 0.0, 0.0, alightName = BUS_STOPS.first())), rideMin = 22),
                Leg(listOf(Candidate(Candidate.Type.METRO, METRO, METRO_STOPS.last(), 0.0, 0.0, alightName = METRO_STOPS.first())), rideMin = 18, transferWalkMin = 4),
            ),
            startMinute = 8 * 60, endMinute = 8 * 60 + 40,
        ),
        trip,
    )

    /** 开始前的倒计时（秒），给录屏的人时间回到桌面；null = 没在倒计时 */
    val countdown = MutableStateFlow<Int?>(null)
    private const val COUNTDOWN = 8

    val snapshot = MutableStateFlow<LiveSnapshot?>(null)
    private var job: Job? = null
    val running get() = job?.isActive == true

    fun start(ctx: Context, speed: Int) {
        if (CommuteService.instance != null) return
        stop(ctx)
        val app = ctx.applicationContext
        job = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            LiveNotifier.ensureChannels(app)
            for (n in COUNTDOWN downTo 1) {
                countdown.value = n
                LiveNotifier.replace(app, LiveNotifier.build(app, LiveNotifier.Visual(
                    title = "演示 $n 秒后开始", text = "可以回到桌面了 · 演示回放", short = "${n}秒", sub = "演示回放",
                    progress = null, stops = 0, trackerRes = R.drawable.ic_metro, metro = true, riding = false,
                )))
                delay(1000)
            }
            countdown.value = null
            val t0 = System.currentTimeMillis()
            // 匀速播放：1 分钟版 = 30×（1 分钟 2 秒，站名、倒计时都看得清），2 分钟版 = 15×
            val rate = if (speed >= 60) 30.0 else 15.0
            var lastKey = ""
            val fired = HashSet<Int>()
            while (isActive) {
                val v = (PRE + (System.currentTimeMillis() - t0) / 1000.0 * rate).toInt().coerceAtMost(END)
                val f = frame(v)
                snapshot.value = f.first
                val key = f.second.title + f.second.text + f.second.progress
                if (key != lastKey) {
                    lastKey = key
                    LiveNotifier.replace(app, LiveNotifier.build(app, f.second))
                }
                ALERTS.forEachIndexed { i, a ->
                    if (v >= a.at && fired.add(i)) LiveNotifier.alert(app, a.title, a.text, metro = a.metro)
                }
                if (v >= END) { delay(5000); break }   // 停在「到家」画面几秒再退出，别一闪而过
                delay(250)
            }
            finish(app)
        }
    }

    fun stop(ctx: Context) {
        val had = job != null
        job?.cancel(); job = null
        if (had) finish(ctx.applicationContext)
    }

    private fun finish(ctx: Context) {
        snapshot.value = null
        countdown.value = null
        job = null
        if (CommuteService.instance == null) runCatching { NotificationManagerCompat.from(ctx).cancel(LiveNotifier.LIVE_ID) }
    }

    private fun hm(v: Int) = "%02d:%02d".format(v / 3600, v / 60 % 60)
    private fun hms(v: Int) = "%02d:%02d:%02d".format(v / 3600, v / 60 % 60, v % 60)
    private fun minsTo(v: Int, t: Int) = maxOf(1, ceil((t - v) / 60.0).toInt())

    private class Alert(val at: Int, val title: String, val text: String, val metro: Boolean = false)

    // 和真实行程一样的弹窗（文案格式取自真实提醒）
    private val ALERTS by lazy { listOf(
        Alert(T0, "现在走 · $METRO", "步行约 3 分钟到${METRO_STOPS.first()} · 本趟约 4 分钟进站 · 下趟约 10 分钟", true),
        Alert(IN - 60, "快到 ${METRO_STOPS.first()} · $METRO 约2分", "步行约 1 分钟到${METRO_STOPS.first()} · 本趟约 2 分钟进站 · 下趟约 8 分钟", true),
        Alert(IN, "已进站 · $METRO", "本趟约 1 分钟进站 · 下趟约 7 分钟", true),
        Alert(XFER - 90, "快到 ${METRO_STOPS.last()} · $BUS 约7分", "步行约 $WALK 分钟到${BUS_STOPS.first()} · 先在站内等，到点叫你"),
        Alert(LEAVE, "现在走 · $BUS", "$BUS 4 分钟到 · 还有 2 站 · 步行 $WALK 分钟"),
        Alert(PROMPT, "快到 ${BUS_STOPS.last()} 了", "准备下车"),
    ) }
    private val LEAVE get() = BUS_ARR - (WALK + 1) * 60   // 步行 3 分钟 + 1 分钟余量 → 现在走

    /** 地铁本趟 / 下趟（6 分钟一班，本趟 12:14 进站） */
    private fun trainText(v: Int): String {
        val cur = RIDE - v
        val curT = if (cur <= 0) "本趟正在站内" else "本趟约 ${maxOf(1, ceil(cur / 60.0).toInt())} 分钟进站"
        return "$curT · 下趟约 ${ceil((cur + 360) / 60.0).toInt()} 分钟"
    }

    /** 回放到 v 这一秒时看板和胶囊该显示什么 */
    fun frame(v: Int): Pair<LiveSnapshot, LiveNotifier.Visual> {
        val l1 = trip.legs[1]
        fun snap(leg: Int, head: String, detail: String, mode: Decision.Mode?, progress: Int? = null, riding: Boolean = false,
                 passed: Int? = null, stops: List<String>? = null) = LiveSnapshot(
            TRIP_ID, trip.name, leg, 2, trip.legs[leg].title(), head, detail, mode, progress, null, riding,
            stopsPassed = passed, stopsTotal = stops?.let { it.size - 1 },
            curStop = if (passed != null && stops != null) stops[passed] else null,
            fromStop = stops?.first(), toStop = stops?.last(),
        )
        val walk = l1.transferWalkMin
        val sn = when {
            v < T0 -> snap(0, "${minsTo(v, T0)}分后走", "按 $BUS 倒推 · ${hm(T0)} 出发 · ${hm(XFER)} 到${METRO_STOPS.last()}换乘",
                Decision.Mode.LEAVE_LATER, progress = ((v - PRE) * 100 / (T0 - PRE)))
            v < IN -> snap(0, "约${minsTo(v, RIDE)}分", "步行约 ${minsTo(v, IN)} 分钟到${METRO_STOPS.first()} · ${trainText(v)}",
                Decision.Mode.METRO, progress = ((v - T0) * 100 / (IN - T0)))
            v < RIDE -> snap(0, "已进站", "${METRO_STOPS.first()} · ${trainText(v)}", Decision.Mode.METRO)
            v < XFER -> {
                val p = ((v - RIDE) * (METRO_STOPS.size - 1) / (XFER - RIDE)).coerceIn(0, METRO_STOPS.size - 2)
                snap(0, "${minsTo(v, XFER)}分钟", "下一站 ${METRO_STOPS[p + 1]} · ${hm(XFER)} 到${METRO_STOPS.last()}换乘",
                    Decision.Mode.METRO, riding = true, passed = p, stops = METRO_STOPS)
            }
            v < LEAVE -> {
                val m = minsTo(v, BUS_ARR)
                val leaveIn = minsTo(v, LEAVE)
                snap(1, "${leaveIn}分后走", "在站内等 · $BUS ${m} 分钟到 · 还有 ${maxOf(1, ceil(m / 2.5).toInt())} 站 · 步行 $walk 分钟",
                    Decision.Mode.LEAVE_LATER)
            }
            v < BUS_ARR - 30 -> snap(1, "现在走", "$BUS ${minsTo(v, BUS_ARR)} 分钟到 · 还有 ${maxOf(1, ceil(minsTo(v, BUS_ARR) / 2.5).toInt())} 站 · 步行 $walk 分钟",
                Decision.Mode.LEAVE_NOW)
            v < BUS_ARR -> snap(1, "车快到了", "$BUS 即将进站 · 你已到${BUS_STOPS.first()}", Decision.Mode.AT_STOP)
            v < TAP -> snap(1, "车到站了", "$BUS 已进站 · ${BUS_STOPS.first()}", Decision.Mode.AT_STOP)
            v < TAP + 30 -> snap(1, "已上车", "刷卡确认上车 · ${hms(TAP)}", null, riding = true, passed = 0, stops = BUS_STOPS)
            v < PROMPT -> {
                val p = ((v - TAP) * (BUS_STOPS.size - 1) / (ALIGHT - TAP)).coerceIn(0, BUS_STOPS.size - 2)
                snap(1, "${BUS_STOPS.size - 1 - p}站", "下一站 ${BUS_STOPS[p + 1]} · 约 ${hm(ALIGHT)} 下车", null,
                    riding = true, passed = p, stops = BUS_STOPS)
            }
            v < ALIGHT -> snap(1, "准备下车", "下一站 ${BUS_STOPS.last()} · 准备下车", null, riding = true,
                passed = BUS_STOPS.size - 2, stops = BUS_STOPS)
            v < HOME -> snap(1, "已到站", "已到 ${BUS_STOPS.last()} · 下车", null, riding = true,
                passed = BUS_STOPS.size - 1, stops = BUS_STOPS)
            else -> snap(1, "到家了", "${hm(HOME)} 到家 · 全程约 ${(HOME - T0) / 60} 分钟", null, riding = true,
                passed = BUS_STOPS.size - 1, stops = BUS_STOPS)
        }
        val metro = sn.legIndex == 0
        val line = if (metro) METRO else BUS
        val prog = sn.progress ?: sn.stopsPassed?.let { p -> sn.stopsTotal?.let { t -> p * 100 / t } }
        val vis = LiveNotifier.Visual(
            title = "${sn.headline} · $line", text = sn.detail, short = sn.headline, sub = "演示回放",
            progress = prog, stops = sn.stopsTotal ?: 0,
            trackerRes = if (metro) R.drawable.ic_metro else R.drawable.ic_bus, metro = metro, riding = sn.riding,
        )
        return sn to vis
    }
}
