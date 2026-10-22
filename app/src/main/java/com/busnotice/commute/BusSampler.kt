package com.busnotice.commute

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 公交到站采样（只记录，不显示、不参与任何判断）。
 *
 * 行程时间窗里（往后多看一段，覆盖到晚上的班次）每 4 分钟查一次行程里每条公交线的实时，
 * 按车辆编号跟踪「还有几秒到」；一辆快到的车下一次查询时不见了，就把它最后一次预估当作实际到站。
 * 攒几天就是自己的到站时刻表，以后拿来算出发窗口。数据只存本机 traces 目录，随「导出行程记录」一起导出。
 */
object BusSampler {
    const val EVERY_MS = 4 * 60_000L
    private const val REQ = 7401
    private const val TAIL_MIN = 150          // 窗口结束后再多看 2.5 小时（晚上末班前后的车）
    private const val LAST_MIN = 23 * 60 + 30
    private const val MAX_GAP_MS = 10 * 60_000L   // 两次采样隔太久（被系统推迟），这次不判到站
    private const val PREFS = "bus_sample"

    /** 一条线 + 一个等车站的匿名键：日志里不出现线路、站点编号 */
    fun key(c: Candidate) = "%08x".format((c.lineId + "|" + c.stationId).hashCode())

    private fun valid(c: Candidate) = c.type == Candidate.Type.BUS && c.lineId.isNotBlank() && c.targetOrder >= 0

    /** 采样窗口（当天分钟数），左闭右开 */
    fun window(t: Trip): IntRange = t.startMinute until minOf(t.endMinute + TAIL_MIN, LAST_MIN)

    private fun activeOn(t: Trip, day: LocalDate) =
        t.enabled && t.legs.any { l -> l.candidates.any { valid(it) } } && t.dayMask and (1 shl (day.dayOfWeek.value - 1)) != 0

    fun inWindowNow(ctx: Context, now: LocalDateTime = LocalDateTime.now()): List<Trip> {
        val m = now.hour * 60 + now.minute
        return TripStore.all(ctx).filter { activeOn(it, now.toLocalDate()) && m in window(it) }
    }

    private fun intent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, REQ, Intent(ctx, BusSampleReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 下一次采样：窗口内 4 分钟后，否则下一个窗口开始时 */
    fun nextAt(trips: List<Trip>, now: LocalDateTime): LocalDateTime? {
        val m = now.hour * 60 + now.minute
        val today = now.toLocalDate()
        if (trips.any { activeOn(it, today) && m in window(it) }) return now.plusSeconds(EVERY_MS / 1000)
        for (d in 0..7) {
            val day = today.plusDays(d.toLong())
            val starts = trips.filter { activeOn(it, day) }.map { window(it).first }
                .filter { d > 0 || it > m }
            val s = starts.minOrNull() ?: continue
            return day.atStartOfDay().plusMinutes(s.toLong())
        }
        return null
    }

    fun schedule(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = intent(ctx)
            am.cancel(pi)
            val at = nextAt(TripStore.all(ctx), LocalDateTime.now()) ?: return
            val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }

    /** 闹钟到了：查一遍窗口内行程的公交线。行程进行中已经查过的（3 分钟内）不重复查 */
    fun sampleAll(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        for (t in inWindowNow(ctx)) for (leg in t.legs) for (c in leg.candidates) {
            if (!valid(c)) continue
            val k = key(c)
            if (!seen.add(k)) continue
            val last = runCatching { JSONObject(prefs.getString(k, "{}") ?: "{}").optLong("t") }.getOrDefault(0L)
            if (now - last < 3 * 60_000L) continue
            val rt = runCatching { ChelaileApi.lineRealtime(c, c.lat, c.lng) }.getOrNull()
            if (rt == null) { write(ctx, JSONObject().put("t", now).put("k", "bus_fail").put("key", k)); continue }
            observe(ctx, c, rt, "bg")
        }
    }

    /** 记下一次实时结果（后台采样或行程中查询都走这里），顺便判断上次快到的车是不是已经到站 */
    @Synchronized
    fun observe(ctx: Context, c: Candidate, rt: ChelaileApi.LineRealtime, src: String) {
        if (!valid(c)) return
        runCatching {
            val k = key(c)
            val now = System.currentTimeMillis()
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prev = runCatching { JSONObject(prefs.getString(k, "{}") ?: "{}") }.getOrDefault(JSONObject())
            val prevT = prev.optLong("t")
            val prevBuses = HashMap<String, Int>()
            prev.optJSONObject("b")?.let { b -> b.keys().forEach { id -> prevBuses[id] = b.optInt(id, -1) } }

            val arr = JSONArray()
            val cur = HashMap<String, Int>()
            rt.buses.forEach { b ->
                arr.put(JSONArray().put(b.busId).put(b.travelSec).put(b.stopsAway).put(b.distanceM))
                if (b.busId.isNotBlank()) cur[b.busId] = b.travelSec
            }
            write(ctx, JSONObject().put("t", now).put("k", "bus").put("key", k).put("src", src)
                .put("tip", rt.tip.take(40)).put("b", arr))

            for ((id, est) in arrivals(prevT, prevBuses, now, cur.keys)) {
                write(ctx, JSONObject().put("t", now).put("k", "bus_arr").put("key", k).put("bus", id)
                    .put("est", est).put("lastEta", prevBuses[id]).put("gapS", (now - prevT) / 1000))
            }
            val b = JSONObject(); cur.forEach { (id, s) -> b.put(id, s) }
            prefs.edit().putString(k, JSONObject().put("t", now).put("b", b).toString()).apply()
        }
    }

    /**
     * 上次还在、这次不见了、上次预估在这段间隔内（多给 2 分钟余量）就会到的车 → 已到站，
     * 到站时间 = 上次查询时间 + 上次预估秒数。间隔太长（被系统推迟）不判，免得把开过去很久的车也算进来。
     */
    fun arrivals(prevT: Long, prev: Map<String, Int>, now: Long, cur: Set<String>): List<Pair<String, Long>> {
        if (prevT <= 0 || now - prevT > MAX_GAP_MS || now <= prevT) return emptyList()
        val gapS = (now - prevT) / 1000
        return prev.filter { (id, eta) -> id !in cur && eta in 0..(gapS + 120) }
            .map { (id, eta) -> id to prevT + eta * 1000L }
            .sortedBy { it.second }
    }

    private fun write(ctx: Context, o: JSONObject) {
        runCatching {
            val dir = File(ctx.filesDir, "traces").apply { mkdirs() }
            File(dir, "${LocalDate.now()}_bus.jsonl").appendText(o.toString() + "\n")
        }
    }
}

class BusSampleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        BusSampler.schedule(ctx)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeoutOrNull(25_000) { BusSampler.sampleAll(ctx) } } finally { pending.finish() }
        }
    }
}
