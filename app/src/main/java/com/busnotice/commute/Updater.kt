package com.busnotice.commute

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 检查 GitHub Release 有没有新版本；仓库公开，无需登录。网络不通就静默跳过 */
object Updater {
    private const val API = "https://api.github.com/repos/ciszawe/BusNotice/releases/latest"
    private const val CHECK_GAP = 15 * 60_000L

    /** 首页点「检查更新」时 +1，弹窗那边强制查一次 */
    val force = androidx.compose.runtime.mutableIntStateOf(0)
    /** 上次检查是否连上了 GitHub */
    @Volatile var lastOk = true

    data class Release(val build: Int, val apkUrl: String, val notes: String)

    fun currentBuild(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }

    /** 有比当前更新的版本就返回它；force=false 时 3 小时内只查一次 */
    suspend fun check(ctx: Context, force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong("last", 0) < CHECK_GAP) return@withContext null
        lastOk = false
        runCatching {
            val c = URL(API).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            sp.edit().putLong("last", now).apply()
            lastOk = true
            // releases 列表按标签字符串排序（build-9 排在 build-25 前），所以只看 GitHub 标记的最新版
            val r = JSONObject(txt)
            val n = Regex("""build-(\d+)""").find(r.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull()
                ?: return@runCatching null
            val assets = r.optJSONArray("assets") ?: return@runCatching null
            var url: String? = null
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
            }
            val best = url?.let { Release(n, it, r.optString("body").take(300)) }
            best?.takeIf { it.build > currentBuild(ctx) && (force || it.build != sp.getInt("skip", 0)) }
        }.getOrNull()
    }

    fun skip(ctx: Context, build: Int) =
        ctx.getSharedPreferences("updater", Context.MODE_PRIVATE).edit().putInt("skip", build).apply()

    /** 下载 APK，onProgress 给 0..1；成功返回文件 */
    suspend fun download(ctx: Context, r: Release, onProgress: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(ctx.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val f = File(dir, "CommuteCapsule-${r.build}.apk")
            val c = URL(r.apkUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
            val total = c.contentLengthLong
            c.inputStream.use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val k = inp.read(buf); if (k < 0) break
                        out.write(buf, 0, k); done += k
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            f
        }.getOrNull()
    }

    /** 拉起系统安装器；没有「安装未知应用」权限时先跳去设置，返回 false */
    fun install(ctx: Context, f: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
