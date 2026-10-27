package com.busnotice.commute

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一次基站采样（TraceLog 每 5 秒查一次服务基站时给出；拿不到的项为 null） */
data class CellSnap(val t: Long, val nrTac: Int?, val nrId: Long?, val lteTac: Int?, val lteId: Int?) {
    /** 4G 基站号（eNB）= 小区号去掉最后 8 位扇区号；同一个站台 / 隧道段的几个扇区归成一个 */
    val enb: Int? get() = lteId?.takeIf { it in 1..0x0FFFFFFF }?.let { it ushr 8 }
}

/**
 * 地铁「地下专网区号」学习（纯逻辑，不碰 Android，方便单测）。
 *
 * 实测：坐地铁时手机几乎全程连着同一个 5G 区号（TAC），进站那一下切进去、出站一分钟内切出来，地面上基本见不到。
 * 学法：每趟地铁结束，全程 ≥ 30 次采样（约 2.5 分钟）里有 80% 以上是同一个区号，就给它记一趟；
 * 坐公交在路上跑的时候见过的区号也记一趟（说明地面也有，不能拿来判断进站）。
 * 「地铁里 ≥ 2 趟、地面 < 2 趟」才算学会。
 */
class MetroTacLearner(
    val rideTrips: MutableMap<Int, Int> = HashMap(),
    val surfaceTrips: MutableMap<Int, Int> = HashMap(),
) {
    private val rideCount = HashMap<Int, Int>()
    private var rideTotal = 0
    private val surfaceSeen = HashSet<Int>()

    fun sampleRide(s: CellSnap) {
        val t = s.nrTac ?: return
        rideTotal++; rideCount[t] = (rideCount[t] ?: 0) + 1
    }

    fun sampleSurface(s: CellSnap) { s.nrTac?.let { surfaceSeen += it } }

    /** 一趟地铁坐完；返回这趟算数的区号（不够格返回 null） */
    fun endRide(): Int? {
        val top = rideCount.maxByOrNull { it.value }
        val ok = top != null && rideTotal >= MIN_SAMPLES && top.value >= rideTotal * SHARE
        val tac = if (ok) top!!.key else null
        if (tac != null) rideTrips[tac] = (rideTrips[tac] ?: 0) + 1
        rideCount.clear(); rideTotal = 0
        return tac
    }

    fun endSurface() {
        surfaceSeen.forEach { surfaceTrips[it] = (surfaceTrips[it] ?: 0) + 1 }
        surfaceSeen.clear()
    }

    fun isMetroTac(tac: Int?): Boolean =
        tac != null && (rideTrips[tac] ?: 0) >= NEED_TRIPS && (surfaceTrips[tac] ?: 0) < NEED_TRIPS

    fun hasLearned(): Boolean = rideTrips.keys.any { isMetroTac(it) }

    fun toJson(): JSONObject = JSONObject()
        .put("ride", JSONObject(rideTrips.mapKeys { it.key.toString() }))
        .put("surface", JSONObject(surfaceTrips.mapKeys { it.key.toString() }))

    companion object {
        const val MIN_SAMPLES = 30
        const val SHARE = 0.8
        const val NEED_TRIPS = 2

        fun fromJson(o: JSONObject?): MetroTacLearner {
            fun read(x: JSONObject?): MutableMap<Int, Int> {
                val m = HashMap<Int, Int>()
                x?.keys()?.forEach { k -> k.toIntOrNull()?.let { m[it] = x.optInt(k) } }
                return m
            }
            return MetroTacLearner(read(o?.optJSONObject("ride")), read(o?.optJSONObject("surface")))
        }
    }
}

/**
 * 进出站判断（纯逻辑）：连续 2 次采样在地下区号 = 进站；进过之后连续 3 次采样在别的区号（约 15 秒）= 出站。
 * 没拿到 5G 区号（掉到 4G）不算离开，免得隧道里信号抖一下就判出站。
 */
class MetroGate(private val isMetro: (Int?) -> Boolean) {
    private var onN = 0
    private var offN = 0
    private var offSince = 0L
    var sawMetro = false; private set

    enum class Edge { ENTER, EXIT }

    fun feed(s: CellSnap): Pair<Edge, Long>? {
        val tac = s.nrTac ?: return null
        return if (isMetro(tac)) {
            onN++; offN = 0
            if (onN == 2) { sawMetro = true; Edge.ENTER to s.t } else null
        } else {
            if (offN == 0) offSince = s.t
            offN++; onN = 0
            if (offN == 3 && sawMetro) { sawMetro = false; Edge.EXIT to offSince } else null
        }
    }

    fun reset() { onN = 0; offN = 0; offSince = 0L; sawMetro = false }
}

/** 一趟地铁沿途的 4G 基站序列：(基站号, 第一次见到, 最后一次见到)。这版只记录，攒够几趟再拿来认站 */
class MetroFpRecorder {
    private val seq = ArrayList<LongArray>()

    /** metro = 这次采样连着地铁地下区号；只记地下的，出站后地面的基站不混进来 */
    fun sample(s: CellSnap, metro: Boolean = true) {
        if (!metro) return
        val e = s.enb?.toLong() ?: return
        val last = seq.lastOrNull()
        if (last != null && last[0] == e) last[2] = s.t else seq += longArrayOf(e, s.t, s.t)
    }

    fun isEmpty() = seq.isEmpty()

    /** 结束这一趟：少于 3 个基站（没真坐起来）返回 null */
    fun finish(): JSONArray? {
        val out = if (seq.size >= 3) JSONArray().apply { seq.forEach { put(JSONArray().put(it[0]).put(it[1]).put(it[2])) } } else null
        seq.clear()
        return out
    }
}

/**
 * E1：按基站估「离下车站还有多久」（纯逻辑）。
 * 每趟只看地下区号里的基站，最后出现的那个基站就是下车站；各趟里「最后一个」出现最多的当终点。
 * 每个基站：终点第一次出现 - 它第一次出现，取各趟中位数；至少 2 趟见过才用。
 */
object MetroEta {
    const val MIN_RIDES = 3

    /** rides：每趟按顺序的 (基站号, 第一次见到的毫秒) */
    fun build(rides: List<List<Pair<Long, Long>>>): Map<Long, Long> {
        val ok = rides.filter { it.size >= 3 }
        if (ok.size < MIN_RIDES) return emptyMap()
        val term = ok.groupingBy { it.last().first }.eachCount().maxByOrNull { it.value } ?: return emptyMap()
        if (term.value * 10 < ok.size * 6) return emptyMap()   // 终点不稳定（60% 以下）不学
        val rem = HashMap<Long, MutableList<Long>>()
        for (r in ok) {
            val tEnd = r.firstOrNull { it.first == term.key }?.second ?: continue
            for ((e, t) in r) if (t <= tEnd) rem.getOrPut(e) { ArrayList() } += tEnd - t
        }
        return rem.filterValues { it.size >= 2 }.mapValues { (_, v) -> v.sorted()[v.size / 2] }
    }

    fun parse(ride: JSONObject): List<Pair<Long, Long>>? {
        if (!ride.optBoolean("mt")) return null   // 旧版记录混着地面基站，不用
        val a = ride.optJSONArray("seq") ?: return null
        val seen = HashSet<Long>()
        return (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { x -> x.optLong(0) to x.optLong(1) } }
            .filter { seen.add(it.first) }
    }
}

/** 本机存取（不进仓库、不上传） */
object MetroCells {
    private const val PREFS = "metro_cells"
    private const val KEEP_RIDES = 60

    fun load(ctx: Context): MetroTacLearner = runCatching {
        MetroTacLearner.fromJson(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tac", null)?.let { JSONObject(it) })
    }.getOrElse { MetroTacLearner() }

    fun save(ctx: Context, l: MetroTacLearner) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tac", l.toJson().toString()).apply()
    }

    private fun fpFile(ctx: Context) = File(ctx.filesDir, "metro_fp.json")

    /** 记一趟地铁的基站序列，按「行程 + 第几段」分开存，每段只留最近 60 趟 */
    fun appendRide(ctx: Context, key: String, ride: JSONObject) = runCatching {
        val f = fpFile(ctx)
        val all = if (f.exists()) JSONObject(f.readText()) else JSONObject()
        val arr = all.optJSONArray(key) ?: JSONArray()
        arr.put(ride)
        while (arr.length() > KEEP_RIDES) arr.remove(0)
        all.put(key, arr)
        f.writeText(all.toString())
    }

    /** E1 用：这一段学到的「基站 → 离下车站还有多少毫秒」 */
    fun eta(ctx: Context, key: String): Map<Long, Long> = runCatching {
        val f = fpFile(ctx); if (!f.exists()) return emptyMap()
        val arr = JSONObject(f.readText()).optJSONArray(key) ?: return emptyMap()
        MetroEta.build((0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(MetroEta::parse) })
    }.getOrDefault(emptyMap())

    fun rideCount(ctx: Context): Int = runCatching {
        val f = fpFile(ctx); if (!f.exists()) return 0
        val all = JSONObject(f.readText())
        all.keys().asSequence().sumOf { all.optJSONArray(it)?.length() ?: 0 }
    }.getOrDefault(0)
}
