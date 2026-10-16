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
 * 现在只记录，不参与任何判断。
 *
 * 每趟行程一个文件 traces/日期_行程.jsonl，每行一条：
 *  loc   定位点：lat lng acc provider spd
 *  cell  服务基站变化：type id tac pci dbm（只在换了基站时记）
 *  acc   加速度：每 2 秒一条，线性加速度幅值的均值 m 和标准差 sd（看列车启停）
 *  ev    状态：上车、换段、判断下车、提醒、手动结束等
 * 保留最近 30 天。
 */
object TraceLog {
    private const val KEEP_DAYS = 30L
    private const val CELL_EVERY_MS = 10_000L

    private var writer: OutputStreamWriter? = null
    private var thread: HandlerThread? = null
    private var h: Handler? = null
    private var sm: SensorManager? = null
    private var lastCell = ""
    private var app: Context? = null

    // 2 秒窗口内的加速度统计
    private var n = 0; private var sum = 0.0; private var sumSq = 0.0; private var winStart = 0L

    private fun dir(ctx: Context) = File(ctx.filesDir, "traces").apply { mkdirs() }

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
        h?.post(cellPoll)
        sm = c.getSystemService(SensorManager::class.java)
        val s = sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        n = 0; sum = 0.0; sumSq = 0.0; winStart = 0L
        if (s != null) sm?.registerListener(accel, s, SensorManager.SENSOR_DELAY_NORMAL, h)
    }

    @Synchronized
    fun stop() {
        if (writer == null) return
        write("ev", JSONObject().put("e", "stop"))
        runCatching { sm?.unregisterListener(accel) }
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

    private val accel = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val linear = e.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION
            val x = e.values[0].toDouble(); val y = e.values[1].toDouble(); val z = e.values[2].toDouble()
            var m = sqrt(x * x + y * y + z * z)
            if (!linear) m -= SensorManager.GRAVITY_EARTH
            val now = System.currentTimeMillis()
            if (winStart == 0L) winStart = now
            n++; sum += m; sumSq += m * m
            if (now - winStart >= 2_000 && n > 0) {
                val mean = sum / n
                val sd = sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
                write("acc", JSONObject().put("m", r(mean, 1000.0)).put("sd", r(sd, 1000.0)).put("n", n))
                n = 0; sum = 0.0; sumSq = 0.0; winStart = now
            }
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
        serving.forEach { ci ->
            val o = JSONObject()
            when (ci) {
                is CellInfoNr -> {
                    val id = ci.cellIdentity as CellIdentityNr
                    o.put("type", "nr").put("id", id.nci).put("tac", id.tac).put("pci", id.pci)
                        .put("dbm", ci.cellSignalStrength.dbm)
                }
                is CellInfoLte -> o.put("type", "lte").put("id", ci.cellIdentity.ci).put("tac", ci.cellIdentity.tac)
                    .put("pci", ci.cellIdentity.pci).put("dbm", ci.cellSignalStrength.dbm)
                is CellInfoWcdma -> o.put("type", "wcdma").put("id", ci.cellIdentity.cid).put("tac", ci.cellIdentity.lac)
                    .put("dbm", ci.cellSignalStrength.dbm)
                is CellInfoGsm -> o.put("type", "gsm").put("id", ci.cellIdentity.cid).put("tac", ci.cellIdentity.lac)
                    .put("dbm", ci.cellSignalStrength.dbm)
                else -> o.put("type", ci.javaClass.simpleName)
            }
            arr.put(o); keys += "${o.optString("type")}:${o.opt("id")}:${o.opt("pci")}"
        }
        // 只在服务基站变化时记一条（信号强度变化不算）
        val key = keys.sorted().joinToString("|")
        if (key == lastCell) return
        lastCell = key
        write("cell", JSONObject().put("cells", arr))
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
