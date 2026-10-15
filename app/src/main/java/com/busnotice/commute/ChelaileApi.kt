package com.busnotice.commute

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 车来了微信小程序 H5 接口（非官方，参考 PeanutSplash/chelaile-mcp）。
 * 随时可能失效，所以只在这里集中封装，外面只用 TransitProvider 的数据类。
 */
object ChelaileApi {
    private const val BASE = "https://web.chelaile.net.cn"
    private const val SALT = "qwihrnbtmj"
    private const val AES_KEY = "FF32AE65FBFD19414EAAFF6291A54B42"
    const val CITY_ID = "076"

    private val DEFAULT = linkedMapOf(
        "s" to "h5", "wxs" to "wx_app", "sign" to "1", "h5RealData" to "1",
        "v" to "3.11.28", "src" to "weixinapp_cx", "ctm_mp" to "mp_wx", "vc" to "2",
        "favoriteGray" to "1", "gpstype" to "wgs", "geo_type" to "wgs", "scene" to "1256",
    )

    private val HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781(0x6700143B) NetType/WIFI MiniProgramEnv/Windows WindowsWechat/WMPF WindowsWechat(0x63090a13) UnifiedPCWindowsWechat(0xf254160a) XWEB/18055",
        "xweb_xhr" to "1",
        "Content-Type" to "text",
        "Accept" to "*/*",
        "Referer" to "https://servicewechat.com/wx71d589ea01ce3321/814/page-frame.html",
        "Accept-Language" to "zh-CN,zh;q=0.9",
    )

    // ---------- 公开数据类 ----------
    data class NearLine(
        val lineId: String, val name: String, val endSn: String, val targetOrder: Int,
    )

    data class NearStop(
        val sId: String, val name: String, val distanceM: Int, val isSubway: Boolean,
        val physicalStId: String, val namesakeStId: String,
        val lines: List<NearLine>,
    )

    data class StopPoint(val sId: String, val name: String, val order: Int, val lat: Double, val lng: Double)

    data class LineDir(val lineId: String, val startSn: String, val endSn: String)

    data class SearchLine(val name: String, val isSubway: Boolean, val dirs: List<LineDir>)

    data class SearchStation(
        val sId: String, val name: String, val lat: Double, val lng: Double,
        val isSubway: Boolean, val physicalStId: String, val namesakeStId: String,
    )

    data class SearchResult(val lines: List<SearchLine>, val stations: List<SearchStation>)

    data class LineDetail(
        val lineId: String, val name: String, val startSn: String, val endSn: String,
        val stations: List<StopPoint>, val reverseLineId: String,
    )

    data class BusEta(
        val busId: String,
        val travelSec: Int,      // 预计还有多少秒到达等车站，<0 表示未知
        val stopsAway: Int,      // 还有几站
        val distanceM: Int,      // 距等车站的路程（米）
    )

    data class LineRealtime(val buses: List<BusEta>, val tip: String)

    // ---------- 接口 ----------

    private fun parseLines(la: org.json.JSONArray?): List<NearLine> =
        if (la == null) emptyList() else (0 until la.length()).mapNotNull { j ->
            val l = la.getJSONObject(j)
            val line = l.optJSONObject("line") ?: return@mapNotNull null
            val target = l.optJSONObject("targetStation")
            val id = line.optString("lineId")
            if (id.isEmpty()) null else NearLine(
                lineId = id,
                name = line.optString("name"),
                endSn = line.optString("endSn"),
                targetOrder = target?.optInt("order", -1) ?: -1,
            )
        }

    private fun parseStops(arr: org.json.JSONArray?): List<NearStop> =
        if (arr == null) emptyList() else (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            NearStop(
                sId = s.optString("sId"), name = s.optString("sn"),
                distanceM = s.optInt("distance"), isSubway = s.optInt("isSubway") == 1,
                physicalStId = s.optString("physicalStId"), namesakeStId = s.optString("namesakeStId"),
                lines = parseLines(s.optJSONArray("lines")),
            )
        }

    /** 附近站点 + 经过的线路（WGS-84 坐标） */
    fun nearbyStops(lat: Double, lng: Double, cityId: String = CITY_ID): List<NearStop> {
        val d = signedGet(
            "/api/bus/stop!encryptedHomePage.action",
            linkedMapOf(
                "cityId" to cityId, "localCityId" to "undefined",
                "lat" to "$lat", "lng" to "$lng", "geo_lat" to "$lat", "geo_lng" to "$lng",
                "type" to "5", "permission" to "0",
            )
        )
        return parseStops(d.optJSONArray("nearSts"))
    }

    /** 按关键字搜索线路和站点 */
    fun search(key: String, cityId: String = CITY_ID): SearchResult {
        val d = signedGet(
            "/api/bus/query!nSearch.action",
            linkedMapOf("cityId" to cityId, "localCityId" to cityId, "key" to key, "supportPhyStn" to "true")
        )
        val r = d.optJSONObject("result") ?: return SearchResult(emptyList(), emptyList())
        val groups = LinkedHashMap<String, MutableList<org.json.JSONObject>>()
        r.optJSONArray("lines")?.let { la ->
            for (i in 0 until la.length()) {
                val l = la.getJSONObject(i)
                val k = l.optString("lineNo").ifEmpty { l.optString("name") + l.optString("lineId") }
                groups.getOrPut(k) { mutableListOf() } += l
            }
        }
        val lines = groups.values.map { g ->
            SearchLine(
                name = g.first().optString("name"),
                isSubway = g.first().optInt("subwayV2") == 1,
                dirs = g.map { LineDir(it.optString("lineId"), it.optString("startSn"), it.optString("endSn")) },
            )
        }
        val stations = mutableListOf<SearchStation>()
        r.optJSONArray("stations")?.let { sa ->
            for (i in 0 until sa.length()) {
                val s = sa.getJSONObject(i)
                var lat = s.optDouble("lat"); var lng = s.optDouble("lng")
                if (s.optString("gpsType") == "gcj") Geo.gcjToWgs(lat, lng).let { lat = it.first; lng = it.second }
                stations += SearchStation(
                    sId = s.optString("sId"), name = s.optString("sn"), lat = lat, lng = lng,
                    isSubway = s.optInt("subwayV2") == 1,
                    physicalStId = s.optString("physicalStId"), namesakeStId = s.optString("namesakeStId"),
                )
            }
        }
        return SearchResult(lines, stations)
    }

    /** 某个站（含对面站台）经过的所有线路 */
    fun stationLines(physicalStId: String, namesakeStId: String, cityId: String = CITY_ID): List<NearStop> {
        val d = signedGet(
            "/api/bus/stop!encryptedPhyStnDetail.action",
            linkedMapOf(
                "cityId" to cityId, "localCityId" to cityId,
                "physicalStId" to physicalStId, "namesakeStId" to namesakeStId,
                "firstLineId" to "", "stationId" to "",
                "lat" to "", "lng" to "", "geo_lat" to "", "geo_lng" to "",
                "actionState" to "1", "permission" to "0",
            )
        )
        return parseStops(d.optJSONArray("stationList"))
    }

    /** 线路详情：全部站点（WGS 坐标）和反方向线路 */
    fun lineDetail(lineId: String, cityId: String = CITY_ID): LineDetail {
        val d = signedGet(
            "/api/bus/line!encryptedLineDetail.action",
            linkedMapOf(
                "cityId" to cityId, "localCityId" to cityId, "lineId" to lineId,
                "lat" to "", "lng" to "", "geo_lat" to "", "geo_lng" to "",
            )
        )
        val st = d.optJSONArray("stations")
        val stations = if (st == null) emptyList() else (0 until st.length()).map { i ->
            val s = st.getJSONObject(i)
            StopPoint(
                sId = s.optString("sId"), name = s.optString("sn"), order = s.optInt("order"),
                lat = s.optDouble("wgsLat", s.optDouble("lat")),
                lng = s.optDouble("wgsLng", s.optDouble("lng")),
            )
        }
        val line = d.optJSONObject("line")
        val other = d.optJSONArray("otherlines")?.optJSONObject(0)?.optString("lineId") ?: ""
        return LineDetail(
            lineId = lineId, name = line?.optString("name") ?: "",
            startSn = line?.optString("startSn") ?: "", endSn = line?.optString("endSn") ?: "",
            stations = stations, reverseLineId = other,
        )
    }

    /** 某线路驶向等车站的车辆实时信息 */
    fun lineRealtime(c: Candidate, lat: Double, lng: Double): LineRealtime {
        val d = signedGet(
            "/api/bus/line!encryptedBusDetail.action",
            linkedMapOf(
                "cshow" to "busDetail", "specail" to "0", "specialType" to "undefined",
                "cityId" to c.cityId, "localCityId" to c.cityId, "lineId" to c.lineId,
                "targetOrder" to "${c.targetOrder}", "specialTargetOrder" to "${c.targetOrder}",
                "stationId" to c.stationId,
                "lat" to "$lat", "lng" to "$lng", "geo_lat" to "$lat", "geo_lng" to "$lng",
            )
        )
        val arr = d.optJSONArray("buses")
        val buses = mutableListOf<BusEta>()
        if (arr != null) for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val order = b.optInt("order", -1)
            if (order < 0 || order > c.targetOrder) continue // 已经开过等车站
            var travel = -1
            b.optJSONArray("travels")?.let { t ->
                for (k in 0 until t.length()) {
                    val tr = t.getJSONObject(k)
                    if (tr.optInt("order") == c.targetOrder) travel = tr.optInt("travelTime", -1)
                }
            }
            buses += BusEta(
                busId = b.optString("busId"),
                travelSec = travel,
                stopsAway = c.targetOrder - order,
                distanceM = b.optInt("distanceToWaitStn", -1),
            )
        }
        buses.sortWith(compareBy({ if (it.travelSec < 0) Int.MAX_VALUE else it.travelSec }, { it.stopsAway }))
        val tip = d.optString("depDesc").ifEmpty { d.optJSONObject("tip")?.optString("desc") ?: "" }
        return LineRealtime(buses, tip)
    }

    // ---------- 底层 ----------

    internal fun sign(params: Map<String, String>): String {
        val s = params.entries.joinToString("&") { "\"${it.key}\"=\"${it.value}\"" } + SALT
        val md = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return md.joinToString("") { "%02x".format(it) }
    }

    internal fun decrypt(cipherB64: String): String {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"))
        return String(c.doFinal(Base64.getDecoder().decode(cipherB64)), Charsets.UTF_8)
    }

    /** 返回体形如 `**YGKJ{...}YGKJ##`，取第一段平衡的 JSON */
    internal fun parseEnvelope(raw: String): JSONObject {
        val start = raw.indexOf('{')
        require(start >= 0) { "上游返回不是 JSON" }
        var depth = 0
        var end = start
        for (i in start until raw.length) {
            when (raw[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth == 0) { end = i + 1; break }
        }
        val env = JSONObject(raw.substring(start, end))
        val data = env.optJSONObject("jsonr")?.optJSONObject("data")
            ?: error("上游返回缺少 jsonr.data")
        val enc = data.optString("encryptResult")
        return if (enc.isNotEmpty()) JSONObject(decrypt(enc)) else data
    }

    private fun signedGet(path: String, extra: LinkedHashMap<String, String>): JSONObject {
        val p = LinkedHashMap(DEFAULT)
        p.putAll(extra)
        p["cryptoSign"] = sign(LinkedHashMap(p))
        val query = p.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val conn = URL("$BASE$path?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        HEADERS.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parseEnvelope(body)
        } finally {
            conn.disconnect()
        }
    }
}
