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
 * 演示：真实记录回放（录屏做宣传片用）。
 *
 * 时间点取自一次真实行程（进站、到换乘站、刷卡上公交、提醒下车、到家），按 30× / 60× 加速，
 * 在首页看板和流体云上按真实界面播放一遍。线路名、站名全部是虚构的，看板上和胶囊上都标「真实记录回放」。
 * 不读任何真实数据、不联网、不写行程记录；正在跑真实行程时不能启动。
 */
object DemoPlayer {
    const val TRIP_ID = "demo-replay"
    const val BADGE = "真实记录回放 · 站点均为虚构"

    private const val METRO = "星河线"
    private const val BUS = "K7路"
    private val METRO_STOPS = listOf("云杉路", "望江", "松柏里", "文昌宫", "白鹭洲", "枫林")
    private val BUS_STOPS = listOf("枫林站东", "槐荫路", "明德桥", "观澜", "青禾巷", "晓月湖", "石泉", "柳岸", "青石桥")

    // 真实记录里的时刻（当天秒数）
    private fun s(h: Int, m: Int, sec: Int = 0) = h * 3600 + m * 60 + sec
    private val PRE = s(12, 2)            // 出发前：按换乘公交倒推的出门时刻
    private val T0 = s(12, 8)
    private val IN = s(12, 12, 41)        // 地下基站认出已进站
    private val RIDE = s(12, 14)
    private val XFER = s(12, 31, 41)      // 到换乘站
    private val BUS_ARR = s(12, 53, 30)
    private val TAP = s(12, 53, 59)       // 刷卡上公交
    private val PROMPT = s(13, 15)        // 提醒准备下车
    private val ALIGHT = s(13, 17)
    private val HOME = s(13, 18)          // 到家
    private val END = HOME + 120

    val trip = Trip(
        id = TRIP_ID, name = "回家", startMinute = 12 * 60, endMinute = 13 * 60 + 20,
        legs = listOf(
            Leg(listOf(Candidate(Candidate.Type.METRO, METRO, METRO_STOPS.first(), 0.0, 0.0, alightName = METRO_STOPS.last())), rideMin = 18),
            Leg(listOf(Candidate(Candidate.Type.BUS, BUS, BUS_STOPS.first(), 0.0, 0.0, alightName = BUS_STOPS.last())), rideMin = 22, transferWalkMin = 4),
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
                    title = "演示 $n 秒后开始", text = "可以回到桌面了 · 真实记录回放", short = "${n}秒", sub = "真实记录回放",
                    progress = null, stops = 0, trackerRes = R.drawable.ic_metro, metro = true, riding = false,
                )))
                delay(1000)
            }
            countdown.value = null
            var vd = PRE.toDouble()
            var last = System.currentTimeMillis()
            var lastKey = ""
            val fired = HashSet<Int>()
            while (isActive) {
                val nowMs = System.currentTimeMillis()
                // 换乘站干等的那段快进 4 倍，其余按选的倍速
                val mult = if (vd > XFER + 120 && vd < BUS_ARR - 7 * 60) 4.0 else 1.0
                vd += (nowMs - last) / 1000.0 * speed * mult; last = nowMs
                val v = vd.toInt().coerceAtMost(END)
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
        Alert(T0, "现在走 · $METRO", "步行约 5 分钟到${METRO_STOPS.first()} · 本趟约 6 分钟进站 · 下趟约 12 分钟", true),
        Alert(IN - 100, "快到 ${METRO_STOPS.first()} · $METRO 约3分", "步行约 2 分钟到${METRO_STOPS.first()} · 本趟约 3 分钟进站 · 下趟约 9 分钟", true),
        Alert(IN, "已进站 · $METRO", "本趟约 1 分钟进站 · 下趟约 7 分钟", true),
        Alert(XFER - 90, "快到 ${METRO_STOPS.last()} · $BUS 约22分", "步行约 4 分钟到${BUS_STOPS.first()} · 先在站内等，到点叫你"),
        Alert(LEAVE, "现在走 · $BUS", "$BUS 6 分钟到 · 还有 2 站 · 步行 4 分钟"),
        Alert(RUN, "快跑 · $BUS", "$BUS 2 分钟到站，步行要 2 分钟，有点悬"),
        Alert(PROMPT, "快到 ${BUS_STOPS.last()} 了", "准备下车 · 真实记录回放"),
    ) }
    private val LEAVE get() = BUS_ARR - 6 * 60   // 公交 6 分钟到、步行 4 分钟 + 余量 → 现在走
    private val RUN get() = BUS_ARR - 2 * 60     // 路上耽搁了：车 2 分钟到、还要走 2 分钟 → 快跑

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
            v < RUN -> snap(1, "现在走", "$BUS ${minsTo(v, BUS_ARR)} 分钟到 · 还有 ${maxOf(1, ceil(minsTo(v, BUS_ARR) / 2.5).toInt())} 站 · 步行 $walk 分钟",
                Decision.Mode.LEAVE_NOW)
            v < BUS_ARR -> snap(1, "快跑", "$BUS ${minsTo(v, BUS_ARR)} 分钟到站，步行要 ${minsTo(v, BUS_ARR)} 分钟，有点悬", Decision.Mode.HURRY)
            v < TAP -> snap(1, "车到站了", "$BUS 已进站 · ${BUS_STOPS.first()}", Decision.Mode.AT_STOP)
            v < TAP + 60 -> snap(1, "已上车", "刷卡确认上车 · ${hms(TAP)}", null, riding = true, passed = 0, stops = BUS_STOPS)
            v < PROMPT -> {
                val p = ((v - TAP) * (BUS_STOPS.size - 1) / (ALIGHT - TAP)).coerceIn(0, BUS_STOPS.size - 3)
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
            title = "${sn.headline} · $line", text = sn.detail, short = sn.headline, sub = "真实记录回放",
            progress = prog, stops = sn.stopsTotal ?: 0,
            trackerRes = if (metro) R.drawable.ic_metro else R.drawable.ic_bus, metro = metro, riding = sn.riding,
        )
        return sn to vis
    }
}
