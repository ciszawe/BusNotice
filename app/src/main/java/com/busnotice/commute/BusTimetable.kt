package com.busnotice.commute

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 按历史到站记录（traces 目录下每天的 _bus.jsonl 里的 bus_arr）学出每个站的「时刻表」。
 * 晚上的公交往往很准点（11/6 晚：换乘站那条线连续两周都是 21:28–21:30 到），
 * 实时接口说「无车」时，用它给出「下一班约 21:29」，并把倒计时交给系统走——进程被冻住也照样对。
 * 只用于显示，不触发「该走了 / 快跑」提醒。
 */
object BusTimetable {
    data class Pred(val atMs: Long, val loMin: Int, val hiMin: Int, val days: Int) {
        fun mid(): Int = java.time.Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute }
    }

    private const val LOOKBACK_DAYS = 28L
    private const val MIN_DAYS = 3
    private const val MAX_SPREAD_MIN = 6
    private const val MAX_AHEAD_MIN = 70

    /** key → (日期 → 当天到站时刻（分钟）列表) */
    private var cache: Map<String, Map<LocalDate, List<Int>>> = emptyMap()
    private var cacheDay: LocalDate? = null

    @Synchronized
    private fun load(ctx: Context): Map<String, Map<LocalDate, List<Int>>> {
        val today = LocalDate.now()
        if (cacheDay == today) return cache
        val zone = ZoneId.systemDefault()
        val out = HashMap<String, HashMap<LocalDate, MutableList<Int>>>()
        val dir = File(ctx.filesDir, "traces")
        val from = today.minusDays(LOOKBACK_DAYS)
        dir.listFiles { f -> f.name.endsWith("_bus.jsonl") }?.forEach { f ->
            val d = runCatching { LocalDate.parse(f.name.take(10)) }.getOrNull() ?: return@forEach
            if (d < from || d >= today) return@forEach
            f.forEachLine { line ->
                if (!line.contains("\"bus_arr\"")) return@forEachLine
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val est = o.optLong("est"); val k = o.optString("key")
                if (est <= 0 || k.isEmpty()) return@forEachLine
                val t = Instant.ofEpochMilli(est).atZone(zone)
                out.getOrPut(k) { HashMap() }.getOrPut(t.toLocalDate()) { mutableListOf() }.add(t.hour * 60 + t.minute)
            }
        }
        cache = out.mapValues { (_, m) -> m.mapValues { (_, l) -> l.sorted() } }
        cacheDay = today
        return cache
    }

    /** 现在之后的下一班（同类日子：工作日 / 周末分开）；历史不够准、不够多就返回 null */
    fun predict(ctx: Context, key: String, now: Long = System.currentTimeMillis()): Pred? = runCatching {
        val byDay = load(ctx)[key] ?: return null
        val zone = ZoneId.systemDefault()
        val t = Instant.ofEpochMilli(now).atZone(zone)
        val p = predictFrom(byDay, t.toLocalDate(), t.hour * 60 + t.minute) ?: return null
        Pred(t.toLocalDate().atStartOfDay(zone).plusMinutes(p.mid.toLong()).toInstant().toEpochMilli(), p.lo, p.hi, p.days)
    }.getOrNull()

    /** 纯计算，方便单测：返回 (中位分钟, 最早, 最晚, 天数) */
    fun predictFrom(byDay: Map<LocalDate, List<Int>>, today: LocalDate, nowMin: Int): Pred4? {
        val weekend = today.dayOfWeek.value >= 6
        val next = byDay.filterKeys { it < today && (it.dayOfWeek.value >= 6) == weekend }.values
            .mapNotNull { l -> l.firstOrNull { it >= nowMin - 1 }?.takeIf { it - nowMin <= MAX_AHEAD_MIN } }
            .sorted()
        if (next.size < MIN_DAYS) return null
        // 去掉最早和最晚各 1 个（≥5 天时），剩下的要挤在 6 分钟内才算「准点」
        val core = if (next.size >= 5) next.subList(1, next.size - 1) else next
        // 各天的「下一班」可能落在两班上（有的天早一班已过）：取最早那一簇
        val first = core.first()
        val cluster = core.filter { it - first <= MAX_SPREAD_MIN }
        if (cluster.size < MIN_DAYS || cluster.size * 2 < core.size) return null
        val mid = cluster[cluster.size / 2]
        return Pred4(mid, cluster.first(), cluster.last(), cluster.size)
    }

    data class Pred4(val mid: Int, val lo: Int, val hi: Int, val days: Int)

    fun hhmm(min: Int) = String.format("%02d:%02d", min / 60, min % 60)
}
