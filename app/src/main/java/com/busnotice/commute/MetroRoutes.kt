package com.busnotice.commute

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 地铁途经站缓存：只存本机，30 天内不重复查 */
object MetroRoutes {
    private const val PREFS = "metro_route"
    private const val TTL = 30L * 24 * 3600 * 1000

    /** 这段地铁坐到哪：最后一段用下车站坐标，换乘段用下一段上车点 */
    fun target(t: Trip, i: Int): Triple<String, Double, Double>? {
        val c = t.legs[i].candidates.firstOrNull { it.isMetro() } ?: return null
        if (!c.alightLat.isNaN()) return Triple(c.alightName.ifEmpty { "下车站" }, c.alightLat, c.alightLng)
        val n = t.legs.getOrNull(i + 1)?.candidates?.firstOrNull() ?: return null
        return Triple(c.alightName.ifEmpty { n.stopName }, n.lat, n.lng)
    }

    private fun key(c: Candidate, lat: Double, lng: Double) =
        "${c.cityId}|${c.name}|${c.stopName}|${c.metroDir}|${"%.3f".format(lat)},${"%.3f".format(lng)}"

    fun cached(ctx: Context, c: Candidate, lat: Double, lng: Double): ChelaileApi.MetroRoute? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(c, lat, lng), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            if (System.currentTimeMillis() - o.getLong("t") > TTL) return null
            val a = o.getJSONArray("s")
            ChelaileApi.MetroRoute((0 until a.length()).map {
                val x = a.getJSONArray(it); ChelaileApi.MetroStation(x.getString(0), x.getDouble(1), x.getDouble(2))
            }, o.getInt("d"))
        }.getOrNull()
    }

    /** 先看缓存，没有再联网查（要在后台线程调用） */
    fun get(ctx: Context, c: Candidate, name: String, lat: Double, lng: Double): ChelaileApi.MetroRoute? {
        cached(ctx, c, lat, lng)?.let { return it }
        val r = runCatching { ChelaileApi.metroRoute(c, name, lat, lng) }.getOrNull() ?: return null
        val s = JSONArray().apply { r.stations.forEach { put(JSONArray().put(it.name).put(it.lat).put(it.lng)) } }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(c, lat, lng), JSONObject().put("t", System.currentTimeMillis()).put("d", r.durSec).put("s", s).toString())
            .apply()
        return r
    }

    /** 按已坐时间推算：下一站名、还剩几站 */
    fun progress(r: ChelaileApi.MetroRoute, elapsedMs: Long, totalMs: Long): Pair<String, Int> {
        val hops = r.hops.coerceAtLeast(1)
        val passed = if (totalMs <= 0) 0 else ((elapsedMs.toDouble() / totalMs) * hops).toInt().coerceIn(0, hops - 1)
        return r.stations[passed + 1].name to (hops - passed)
    }
}
