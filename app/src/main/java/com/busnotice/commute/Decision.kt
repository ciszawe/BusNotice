package com.busnotice.commute

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    fun distanceM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    // GCJ-02 → WGS-84（近似反算，误差 1–2 米）
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun gcjToWgs(lat: Double, lng: Double): Pair<Double, Double> {
        val (dLat, dLng) = delta(lat, lng)
        return Pair(lat - dLat, lng - dLng)
    }

    private fun delta(lat: Double, lng: Double): Pair<Double, Double> {
        var dLat = tLat(lng - 105.0, lat - 35.0)
        var dLng = tLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * Math.PI)
        return Pair(dLat, dLng)
    }

    private fun tLat(x: Double, y: Double): Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        r += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return r
    }

    private fun tLng(x: Double, y: Double): Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        r += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return r
    }
}

/**
 * 决策：纯规则，不依赖 Android，可单测。
 * 同一段里可以有多个选项（几路公交 + 通勤车），挑「赶得上的最早那班」。
 */
object Decision {
    const val WALK_SPEED = 1.2        // 米/秒
    const val DETOUR = 1.3            // 直线距离到实际路程的系数
    const val AT_STOP_M = 60.0        // 认为已在站台的距离
    const val SAFETY_SEC = 60         // 留给红绿灯和找站台的余量

    enum class Mode { LEAVE_LATER, LEAVE_NOW, HURRY, AT_STOP, NO_BUS, METRO, CONNECT, CONNECT_MISS }

    /** 某个选项的一班车：还有多少秒到上车点 */
    data class Arrival(val etaSec: Int, val stopsAway: Int = -1, val busId: String = "")

    /** 一个选项此刻的状态 */
    data class Option(
        val candidate: Candidate,
        val walkSec: Int,                // 走到上车点要多久（换乘时 = 剩余乘车 + 换乘步行）
        val arrivals: List<Arrival>,     // 按 eta 排序；eta<0 表示只知道站数
        val tip: String = "",
    )

    data class Advice(
        val mode: Mode,
        val option: Option?,
        val arrival: Arrival?,
        val walkSec: Int,
        val leaveInSec: Int,   // 还剩多少秒该出发
        val headline: String,  // 胶囊短文本
        val detail: String,    // 通知正文
        val next: Pair<Option, Arrival>? = null,
    )

    fun walkSeconds(distM: Double): Int =
        if (distM <= AT_STOP_M) 0 else (distM * DETOUR / WALK_SPEED).toInt()

    /** 通勤车：按时刻表算出接下来几班还有多少秒 */
    fun shuttleArrivals(c: Candidate, nowSecOfDay: Int): List<Arrival> =
        c.departures.sorted().map { it * 60 - nowSecOfDay }.filter { it >= -30 }.take(3)
            .map { Arrival(it.coerceAtLeast(0)) }

    fun decide(options: List<Option>, connecting: Boolean = false): Advice {
        if (options.isEmpty()) return Advice(Mode.NO_BUS, null, null, 0, 0, "未设置", "这段行程还没有添加线路")

        // 全是地铁：没有实时数据，只提示步行
        if (options.all { it.candidate.type == Candidate.Type.METRO }) {
            val o = options.minBy { it.walkSec }
            val hw = o.candidate.headwayMin
            val hwText = if (hw > 0) " · 约 $hw 分钟一班" else ""
            return if (o.walkSec == 0) Advice(Mode.METRO, o, null, 0, 0, "进站", "已到 ${o.candidate.stopName}$hwText")
            else Advice(Mode.METRO, o, null, o.walkSec, 0, "去地铁",
                "步行约 ${mins(o.walkSec)} 分钟到 ${o.candidate.stopName}$hwText")
        }

        val all = options.flatMap { o -> o.arrivals.map { o to it } }
        val known = all.filter { it.second.etaSec >= 0 }.sortedBy { it.second.etaSec }
        if (known.isEmpty()) {
            val b = all.minByOrNull { if (it.second.stopsAway < 0) Int.MAX_VALUE else it.second.stopsAway }
            val tip = options.map { it.tip }.firstOrNull { it.isNotEmpty() } ?: ""
            val detail = when {
                b != null && b.second.stopsAway >= 0 -> "${b.first.candidate.label()} 最近一班还有 ${b.second.stopsAway} 站" +
                    if (tip.isNotEmpty()) " · $tip" else ""
                tip.isNotEmpty() -> tip
                else -> "暂无车辆信息"
            }
            val head = if (b != null && b.second.stopsAway >= 0) "${b.second.stopsAway}站" else "无车"
            return Advice(Mode.NO_BUS, b?.first ?: options.first(), b?.second, options.first().walkSec, 0, head, detail)
        }

        fun ok(p: Pair<Option, Arrival>) =
            if (p.first.walkSec == 0) p.second.etaSec >= 0 else p.second.etaSec >= p.first.walkSec + SAFETY_SEC

        val catchable = known.firstOrNull { ok(it) }
        fun nextAfter(cur: Pair<Option, Arrival>) =
            known.firstOrNull { it !== cur && it.second.etaSec > cur.second.etaSec && ok(it) }

        if (connecting) {
            if (catchable == null) {
                val first = known.first()
                return Advice(Mode.CONNECT_MISS, first.first, first.second, first.first.walkSec, 0, "换乘紧",
                    "约 ${mins(first.first.walkSec)} 分钟到 ${first.first.candidate.stopName}，" +
                        "${first.first.candidate.label()} ${mins(first.second.etaSec)} 分钟到，可能赶不上")
            }
            val (o, a) = catchable
            val wait = a.etaSec - o.walkSec
            return Advice(Mode.CONNECT, o, a, o.walkSec, 0, "换${o.candidate.label()}",
                "约 ${mins(o.walkSec)} 分钟到 ${o.candidate.stopName} · ${o.candidate.label()} " +
                    "${mins(a.etaSec)} 分钟到，等约 ${mins(wait)} 分钟", nextAfter(catchable))
        }

        if (catchable == null) {
            val first = known.first()
            return Advice(Mode.HURRY, first.first, first.second, first.first.walkSec, 0, "快跑",
                "${first.first.candidate.label()} ${mins(first.second.etaSec)} 分钟到站，" +
                    "步行要 ${mins(first.first.walkSec)} 分钟，有点悬")
        }

        val (o, a) = catchable
        val next = nextAfter(catchable)
        val nextText = next?.let { " · 下一班 ${it.first.candidate.label()} ${mins(it.second.etaSec)} 分" } ?: ""
        val stopsText = if (a.stopsAway >= 0) " · 还有 ${a.stopsAway} 站" else ""
        if (o.walkSec == 0) {
            return Advice(Mode.AT_STOP, o, a, 0, 0,
                if (a.etaSec < 60) "进站" else "${mins(a.etaSec)}分",
                "${o.candidate.label()} 约 ${mins(a.etaSec)} 分钟到$stopsText$nextText", next)
        }
        val leaveIn = a.etaSec - o.walkSec - SAFETY_SEC
        val base = "${o.candidate.label()} ${mins(a.etaSec)} 分钟到$stopsText · 步行 ${mins(o.walkSec)} 分钟$nextText"
        return if (leaveIn <= 60) {
            Advice(Mode.LEAVE_NOW, o, a, o.walkSec, leaveIn.coerceAtLeast(0), "现在走", base, next)
        } else {
            Advice(Mode.LEAVE_LATER, o, a, o.walkSec, leaveIn, "${mins(leaveIn)}分后走", base, next)
        }
    }

    fun mins(sec: Int) = ((sec + 30) / 60).coerceAtLeast(0)
}
