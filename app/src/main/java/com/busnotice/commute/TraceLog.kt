package com.busnotice.commute

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.TelephonyManager
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sqrt

/**
 * 行程记录（只存手机本地，不上传）：为以后做「轨迹匹配 / 地下停站计数 / 基站序列匹配」攒真实数据。
 * 大部分只记录；加速度窗口另外留最近几分钟在内存里，给下地铁判断当佐证（AccMotion）。
 *
 * 每趟行程一个文件 traces/日期_行程.jsonl，每行一条：
 *  loc   定位点：lat lng acc provider spd
 *  cell  服务基站变化：type id tac pci dbm（只在换了基站时记）
 *  acc   加速度：每 2 秒一条，线性加速度幅值的均值 m 和标准差 sd（看列车启停），
 *        三轴均值 ax ay az、重力向量均值 gx gy gz（离线投影到水平面看加减速），ts = 按传感器时间戳算的窗口结束时刻
 *  env   磁场 + 气压：每 2 秒一条。mm/msd = 磁场幅值均值/标准差（μT，列车牵引电机启停时会大幅波动），
 *        p = 气压均值（hPa，下扶梯约 −0.1 hPa/米、进出隧道有压力波）；手机没有对应传感器就不写那一项
 *  ev    状态：上车、换段、判断下车、提醒、手动结束等
 * 保留最近 30 天。
 */
object TraceLog {
    private const val KEEP_DAYS = 30L
    private const val CELL_EVERY_MS = 5_000L   // 地铁站间约 2 分钟，5 秒一查才不漏掉站台那一下的基站切换

    private var writer: OutputStreamWriter? = null
    private var thread: HandlerThread? = null
    private var h: Handler? = null
    private var sm: SensorManager? = null
    private var lastCell = ""
    private var app: Context? = null

    // 2 秒窗口内的加速度统计（时间按传感器事件时间戳，批量上报时也不会挤成一个窗口）
    private var n = 0; private var sum = 0.0; private var sumSq = 0.0; private var winStart = 0L; private var lastT = 0L
    private var sx = 0.0; private var sy = 0.0; private var sz = 0.0
    private var gn = 0; private var gx = 0.0; private var gy = 0.0; private var gz = 0.0
    // 磁场 / 气压 2 秒窗口（只记录，暂不参与判断）
    private var mn = 0; private var msum = 0.0; private var msq = 0.0; private var pn = 0; private var psum = 0.0; private var envStart = 0L
    private const val LATENCY_US = 10_000_000   // 允许传感器攒 10 秒再报：息屏 doze 时数据也能到

    // 最近约 15 分钟的窗口，给下车判断用
    private val recent = ArrayDeque<AccMotion.Win>()
    private const val RECENT_MAX = 450   // 约 15 分钟：等车上车的判断要回看进站以来的全部窗口

    fun recentWindows(): List<AccMotion.Win> = synchronized(recent) { recent.toList() }

    // 服务基站切换的时刻（最近 30 个）：坐地铁时列车在动 → 基站每一两分钟换一次；人不动 → 长时间不换。
    // 只在行程记录开着时更新（5 秒查一次）。
    private val cellChanges = ArrayDeque<Long>()
    @Volatile var cellSince = 0L; private set     // 当前服务基站从什么时候开始一直没换（记录开始时 = 开始时刻）

    @Synchronized private fun noteCellChange(t: Long) {
        cellChanges.addLast(t); while (cellChanges.size > 30) cellChanges.removeFirst()
        cellSince = t
    }

    /** t 之后第一次换基站的时刻；没有返回 null */
    @Synchronized fun cellChangeAfter(t: Long): Long? = cellChanges.firstOrNull { it > t }

    /** 基站已经多久没换了（毫秒）；记录没开返回 null */
    fun cellStillMs(now: Long): Long? = if (writer == null || cellSince == 0L) null else now - cellSince

    private fun dir(ctx: Context) = File(ctx.filesDir, "traces").apply { mkdirs() }

    /** 每次查基站（5 秒一次，不管变没变）都回调一次，给地铁进出站 / 基站学习用；在记录线程上调用 */
    @Volatile var onCell: ((CellSnap) -> Unit)? = null

    @Synchronized
    fun start(ctx: Context, tripId: String, tripName: String) {
        stop()
        val c = ctx.applicationContext; app = c
        prune(c)
        val f = File(dir(c), "${LocalDate.now()}_${tripId.take(8)}.jsonl")
        writer = OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8)
        val t = HandlerThread("trace").apply { start() }; thread = t
        h = Handler(t.looper)
        lastCell = ""
        write("ev", JSONObject().put("e", "start").put("trip", tripName))
        synchronized(this) { cellChanges.clear() }; cellSince = System.currentTimeMillis()
        h?.post(cellPoll)
        sm = c.getSystemService(SensorManager::class.java)
        // 优先用唤醒型传感器：息屏后也会按批量延迟把数据送上来（不另外持有唤醒锁）
        fun pick(type: Int) = sm?.getDefaultSensor(type, true) ?: sm?.getDefaultSensor(type)
        val s = pick(Sensor.TYPE_LINEAR_ACCELERATION) ?: pick(Sensor.TYPE_ACCELEROMETER)
        val g = pick(Sensor.TYPE_GRAVITY)
        resetWin(); synchronized(recent) { recent.clear() }
        if (s != null) sm?.registerListener(accel, s, SensorManager.SENSOR_DELAY_NORMAL, LATENCY_US, h)
        if (g != null) sm?.registerListener(accel, g, SensorManager.SENSOR_DELAY_NORMAL, LATENCY_US, h)
        val mag = pick(Sensor.TYPE_MAGNETIC_FIELD)
        val baro = pick(Sensor.TYPE_PRESSURE)
        resetEnv()
        if (mag != null) sm?.registerListener(env, mag, SensorManager.SENSOR_DELAY_NORMAL, LATENCY_US, h)
        if (baro != null) sm?.registerListener(env, baro, SensorManager.SENSOR_DELAY_NORMAL, LATENCY_US, h)
        write("ev", JSONObject().put("e", "sensors").put("mag", mag != null).put("baro", baro != null)
            .put("lin", s?.type == Sensor.TYPE_LINEAR_ACCELERATION))
    }

    private fun resetEnv() { mn = 0; msum = 0.0; msq = 0.0; pn = 0; psum = 0.0; envStart = 0L }

    private val env = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val t = evTime(e.timestamp)
            if (envStart == 0L) envStart = t
            if (e.sensor.type == Sensor.TYPE_PRESSURE) { pn++; psum += e.values[0] }
            else {
                val x = e.values[0].toDouble(); val y = e.values[1].toDouble(); val z = e.values[2].toDouble()
                val m = sqrt(x * x + y * y + z * z); mn++; msum += m; msq += m * m
            }
            if (t - envStart >= 2_000 || t < envStart) {
                val o = JSONObject().put("ts", t)
                if (mn > 0) {
                    val mean = msum / mn
                    o.put("mm", r(mean, 10.0)).put("msd", r(sqrt((msq / mn - mean * mean).coerceAtLeast(0.0)), 100.0))
                }
                if (pn > 0) o.put("p", r(psum / pn, 100.0))
                write("env", o)
                resetEnv()
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    @Synchronized
    fun stop() {
        if (writer == null) return
        write("ev", JSONObject().put("e", "stop"))
        runCatching { sm?.unregisterListener(accel) }
        runCatching { sm?.unregisterListener(env) }
        h?.removeCallbacksAndMessages(null)
        thread?.quitSafely(); thread = null; h = null
        runCatching { writer?.close() }; writer = null
    }

    fun event(name: String, vararg kv: Pair<String, Any?>) {
        if (writer == null) return
        val o = JSONObject().put("e", name)
        kv.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        write("ev", o)
    }

    fun loc(l: Location) {
        if (writer == null) return
        write("loc", JSONObject()
            .put("lat", r(l.latitude, 1e6)).put("lng", r(l.longitude, 1e6))
            .put("acc", l.accuracy.toInt()).put("p", l.provider ?: "")
            .put("spd", if (l.hasSpeed()) r(l.speed.toDouble(), 10.0) else JSONObject.NULL)
            .put("ft", l.time))
    }

    private fun r(v: Double, f: Double) = Math.round(v * f) / f

    @Synchronized
    private fun write(type: String, o: JSONObject) {
        val w = writer ?: return
        runCatching {
            o.put("t", System.currentTimeMillis()).put("k", type)
            w.write(o.toString()); w.write("\n"); w.flush()
        }
    }

    private fun resetWin() {
        n = 0; sum = 0.0; sumSq = 0.0; winStart = 0L; lastT = 0L
        sx = 0.0; sy = 0.0; sz = 0.0
        gn = 0; gx = 0.0; gy = 0.0; gz = 0.0
    }

    /** 传感器时间戳（开机以来纳秒）换成墙钟毫秒；对不上就用现在 */
    private fun evTime(ts: Long): Long {
        val now = System.currentTimeMillis()
        val t = now - (SystemClock.elapsedRealtimeNanos() - ts) / 1_000_000
        return if (t > now + 5_000 || t < now - 10 * 60_000L) now else t
    }

    /** 收掉当前窗口：写一条 acc，并放进内存里的最近窗口 */
    private fun flush(end: Long) {
        if (n == 0) return
        val mean = sum / n
        val sd = sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
        val o = JSONObject().put("m", r(mean, 1000.0)).put("sd", r(sd, 1000.0)).put("n", n)
            .put("ax", r(sx / n, 1000.0)).put("ay", r(sy / n, 1000.0)).put("az", r(sz / n, 1000.0))
            .put("ts", end)
        if (gn > 0) o.put("gx", r(gx / gn, 1000.0)).put("gy", r(gy / gn, 1000.0)).put("gz", r(gz / gn, 1000.0))
        write("acc", o)
        synchronized(recent) {
            recent.addLast(AccMotion.Win(end, mean, sd))
            while (recent.size > RECENT_MAX) recent.removeFirst()
        }
        resetWin()
    }

    private val accel = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0].toDouble(); val y = e.values[1].toDouble(); val z = e.values[2].toDouble()
            if (e.sensor.type == Sensor.TYPE_GRAVITY) { gn++; gx += x; gy += y; gz += z; return }
            val linear = e.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION
            var m = sqrt(x * x + y * y + z * z)
            if (!linear) m -= SensorManager.GRAVITY_EARTH
            val t = evTime(e.timestamp)
            // 传感器断档（息屏被挂起）：先把断档前的窗口收掉，别把空白算进一个窗口
            if (n > 0 && t - lastT > 4_000) flush(lastT)
            if (n == 0) winStart = t
            n++; sum += m; sumSq += m * m
            sx += x; sy += y; sz += z
            lastT = t
            if (t - winStart >= 2_000) flush(t)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val cellPoll = object : Runnable {
        override fun run() {
            runCatching { readCell() }
            h?.postDelayed(this, CELL_EVERY_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readCell() {
        val c = app ?: return
        val tm = c.getSystemService(TelephonyManager::class.java) ?: return
        val infos: List<CellInfo> = tm.allCellInfo ?: return
        val serving = infos.filter { it.isRegistered }
        if (serving.isEmpty()) return
        val arr = org.json.JSONArray()
        val keys = mutableListOf<String>()
        var nrTac: Int? = null; var nrId: Long? = null; var lteTac: Int? = null; var lteId: Int? = null
        fun okInt(v: Int) = v.takeIf { it in 0 until Int.MAX_VALUE }
        serving.forEach { ci ->
            val o = JSONObject()
            when (ci) {
                is CellInfoNr -> {
                    val id = ci.cellIdentity as CellIdentityNr
                    o.put("type", "nr").put("id", id.nci).put("tac", id.tac).put("pci", id.pci)
                        .put("dbm", ci.cellSignalStrength.dbm)
                    if (nrTac == null) { nrTac = okInt(id.tac); nrId = id.nci.takeIf { it in 0 until Long.MAX_VALUE } }
                }
                is CellInfoLte -> {
                    o.put("type", "lte").put("id", ci.cellIdentity.ci).put("tac", ci.cellIdentity.tac)
                        .put("pci", ci.cellIdentity.pci).put("dbm", ci.cellSignalStrength.dbm)
                    if (lteId == null) { lteId = okInt(ci.cellIdentity.ci); lteTac = okInt(ci.cellIdentity.tac) }
                }
                is CellInfoWcdma -> o.put("type", "wcdma").put("id", ci.cellIdentity.cid).put("tac", ci.cellIdentity.lac)
                    .put("dbm", ci.cellSignalStrength.dbm)
                is CellInfoGsm -> o.put("type", "gsm").put("id", ci.cellIdentity.cid).put("tac", ci.cellIdentity.lac)
                    .put("dbm", ci.cellSignalStrength.dbm)
                else -> o.put("type", ci.javaClass.simpleName)
            }
            arr.put(o); keys += "${o.optString("type")}:${o.opt("id")}:${o.opt("pci")}"
        }
        onCell?.let { cb -> runCatching { cb(CellSnap(System.currentTimeMillis(), nrTac, nrId, lteTac, lteId)) } }
        // 只在服务基站变化时记一条（信号强度变化不算）
        val key = keys.sorted().joinToString("|")
        if (key == lastCell) return
        val first = lastCell.isEmpty()
        lastCell = key
        if (!first) noteCellChange(System.currentTimeMillis())
        // 顺带记最强的几个邻区（只有 pci + 信号）：以后做「站点基站指纹」时比单个服务基站更稳
        val nb = org.json.JSONArray()
        infos.filter { !it.isRegistered }.mapNotNull { ci ->
            when (ci) {
                is CellInfoNr -> Triple("nr", (ci.cellIdentity as CellIdentityNr).pci, ci.cellSignalStrength.dbm)
                is CellInfoLte -> Triple("lte", ci.cellIdentity.pci, ci.cellSignalStrength.dbm)
                else -> null
            }
        }.filter { it.second in 0..1007 }.sortedByDescending { it.third }.take(4)
            .forEach { (ty, pci, dbm) -> nb.put(JSONObject().put("type", ty).put("pci", pci).put("dbm", dbm)) }
        val o = JSONObject().put("cells", arr)
        if (nb.length() > 0) o.put("nb", nb)
        write("cell", o)
    }

    private fun prune(ctx: Context) {
        val cut = LocalDate.now().minusDays(KEEP_DAYS).toString()
        dir(ctx).listFiles()?.forEach { if (it.name.take(10) < cut) it.delete() }
    }

    fun dayCount(ctx: Context): Int =
        dir(ctx).listFiles()?.map { it.name.take(10) }?.distinct()?.size ?: 0

    /** 打包所有记录，用系统分享发出去（由用户自己选发到哪） */
    fun share(ctx: Context): Boolean {
        val files = dir(ctx).listFiles()?.filter { it.length() > 0 }?.sortedBy { it.name } ?: return false
        if (files.isEmpty()) return false
        val out = File(ctx.cacheDir, "export").apply { mkdirs() }
        val zip = File(out, "commute-traces-${LocalDate.now()}.zip")
        ZipOutputStream(FileOutputStream(zip)).use { z ->
            files.forEach { f -> z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", zip)
        ctx.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "导出行程记录")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
