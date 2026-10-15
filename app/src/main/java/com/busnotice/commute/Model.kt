package com.busnotice.commute

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 一个可以上车的选项：实时公交、自定义通勤车（时刻表）、地铁（按间隔估算） */
data class Candidate(
    val type: Type,
    val name: String,            // 公交线路名 / 通勤车名称 / 地铁线路名
    val stopName: String,        // 上车点
    val lat: Double,             // WGS-84
    val lng: Double,
    // 公交
    val cityId: String = ChelaileApi.CITY_ID,
    val lineId: String = "",
    val stationId: String = "",
    val endSn: String = "",
    val targetOrder: Int = -1,
    val alightName: String = "", // 下车站（可选，用于生成回程）
    // 通勤车：一天中的发车分钟数
    val departures: List<Int> = emptyList(),
    // 地铁：发车间隔（分钟）
    val headwayMin: Int = 0,
) {
    enum class Type { BUS, SHUTTLE, METRO }

    fun label() = when (type) {
        Type.BUS -> "${name}路"
        Type.SHUTTLE -> name
        Type.METRO -> name.ifEmpty { "地铁" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name).put("name", name).put("stopName", stopName)
        .put("lat", lat).put("lng", lng).put("cityId", cityId).put("lineId", lineId)
        .put("stationId", stationId).put("endSn", endSn).put("targetOrder", targetOrder)
        .put("alightName", alightName).put("departures", JSONArray(departures)).put("headwayMin", headwayMin)

    companion object {
        fun fromJson(o: JSONObject): Candidate {
            val deps = o.optJSONArray("departures")
            return Candidate(
                type = runCatching { Type.valueOf(o.getString("type")) }.getOrDefault(Type.BUS),
                name = o.optString("name"), stopName = o.optString("stopName"),
                lat = o.optDouble("lat"), lng = o.optDouble("lng"),
                cityId = o.optString("cityId", ChelaileApi.CITY_ID), lineId = o.optString("lineId"),
                stationId = o.optString("stationId"), endSn = o.optString("endSn"),
                targetOrder = o.optInt("targetOrder", -1), alightName = o.optString("alightName"),
                departures = if (deps == null) emptyList() else (0 until deps.length()).map { deps.getInt(it) },
                headwayMin = o.optInt("headwayMin"),
            )
        }
    }
}

/** 行程中的一段：在同一处换乘点可选的若干交通方式，哪个先来坐哪个 */
data class Leg(
    val candidates: List<Candidate>,
    val rideMin: Int = 10,          // 这段大约坐多久
    val transferWalkMin: Int = 3,   // 从上一段下车走到这里要多久（第一段忽略）
) {
    fun title(): String = candidates.joinToString(" / ") { it.label() }.ifEmpty { "未设置" }
    fun stopName(): String = candidates.map { it.stopName }.distinct().joinToString(" / ")
    fun isMetroOnly() = candidates.isNotEmpty() && candidates.all { it.type == Candidate.Type.METRO }

    fun toJson(): JSONObject = JSONObject()
        .put("candidates", JSONArray().apply { candidates.forEach { put(it.toJson()) } })
        .put("rideMin", rideMin).put("transferWalkMin", transferWalkMin)

    companion object {
        fun fromJson(o: JSONObject): Leg {
            val arr = o.optJSONArray("candidates") ?: JSONArray()
            return Leg(
                candidates = (0 until arr.length()).map { Candidate.fromJson(arr.getJSONObject(it)) },
                rideMin = o.optInt("rideMin", 10), transferWalkMin = o.optInt("transferWalkMin", 3),
            )
        }
    }
}

/** 一条通勤行程：哪些天、什么时间窗、依次坐哪几段 */
data class Trip(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "上班",
    val legs: List<Leg> = emptyList(),
    val dayMask: Int = 0b0011111,   // bit0=周一 ... bit6=周日
    val startMinute: Int = 8 * 60,  // 出发时间窗
    val endMinute: Int = 8 * 60 + 30,
    val warmupMin: Int = 10,
    val enabled: Boolean = true,
) {
    fun timeText() = "%02d:%02d–%02d:%02d".format(startMinute / 60, startMinute % 60, endMinute / 60, endMinute % 60)
    fun daysText(): String {
        if (dayMask == 0b0011111) return "工作日"
        if (dayMask == 0b1111111) return "每天"
        if (dayMask == 0b1100000) return "周末"
        val n = arrayOf("一", "二", "三", "四", "五", "六", "日")
        return "周" + (0..6).filter { dayMask and (1 shl it) != 0 }.joinToString("") { n[it] }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("legs", JSONArray().apply { legs.forEach { put(it.toJson()) } })
        .put("dayMask", dayMask).put("startMinute", startMinute).put("endMinute", endMinute)
        .put("warmupMin", warmupMin).put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject): Trip {
            val arr = o.optJSONArray("legs") ?: JSONArray()
            return Trip(
                id = o.getString("id"), name = o.optString("name", "通勤"),
                legs = (0 until arr.length()).map { Leg.fromJson(arr.getJSONObject(it)) },
                dayMask = o.optInt("dayMask", 0b0011111),
                startMinute = o.optInt("startMinute", 480), endMinute = o.optInt("endMinute", 510),
                warmupMin = o.optInt("warmupMin", 10), enabled = o.optBoolean("enabled", true),
            )
        }
    }
}

object TripStore {
    private const val PREFS = "trips"
    private const val KEY = "list"

    fun all(ctx: Context): List<Trip> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { runCatching { Trip.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    fun get(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    fun save(ctx: Context, trips: List<Trip>) {
        val arr = JSONArray()
        trips.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun upsert(ctx: Context, t: Trip) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == t.id }
        if (i >= 0) list[i] = t else list += t
        save(ctx, list)
    }

    fun delete(ctx: Context, id: String) = save(ctx, all(ctx).filter { it.id != id })
}
