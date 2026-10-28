package com.busnotice.commute

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 出行识别（可选，无障碍）：只在有行程运行时，读系统出行胶囊 / 交通卡弹窗上的文字，
 * 带出行关键词的才记进本机行程记录；系统刷卡弹窗（已进站 / 已出站 / 消费成功）当作刷卡信号交给行程判断。
 */
class TransitA11y : AccessibilityService() {

    private var lastText = ""
    private var lastAt = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        if (CommuteService.instance == null && !windowOpen()) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        runCatching { handle(e, pkg) }
    }

    private fun handle(e: AccessibilityEvent, pkg: String) {
        val parts = LinkedHashSet<String>()
        e.text?.forEach { t -> t?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it } }
        e.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        val budget = intArrayOf(MAX_NODES)
        e.source?.let { collect(it, 0, parts, budget) }
        if (!KEYWORDS.containsMatchIn(parts.joinToString(" "))) {
            // 事件本身没带出行字样：再看当前窗口和系统窗口（胶囊、弹窗多在系统窗口里）
            rootInActiveWindow?.let { collect(it, 0, parts, budget) }
            runCatching {
                windows.filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }.forEach { w ->
                    w.root?.let { collect(it, 0, parts, budget) }
                }
            }
        }
        val text = parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty() || !KEYWORDS.containsMatchIn(text)) return
        val now = System.currentTimeMillis()
        // 同一段文字 30 秒内只记一次
        if (text == lastText && now - lastAt < 30_000) return
        lastText = text; lastAt = now
        // 只记系统界面 / 钱包的文字：别的 App 屏幕内容不落盘（隐私）
        if (pkg.contains("systemui") || pkg.contains("wallet")) TraceLog.event("a11y", "pkg" to pkg, "text" to text.take(120))
        tapSignal(text, pkg, now)
        BALANCE.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let { v ->
            val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prev = p.getFloat("balance", Float.NaN)
            val delta = if (prev.isNaN()) null else Math.round((v - prev) * 100) / 100.0
            if (prev.isNaN() || delta != 0.0) {
                val prevAt = p.getLong("balanceAt", 0L)
                p.edit().putFloat("balance", v.toFloat()).putLong("balanceAt", now).apply()
                // 余额刚少了一笔车费 = 刚刷了卡。上一次余额必须是 3 小时内看到的，
                // 否则可能是之前没看到的扣费（比如打开钱包时才发现少了），不能当成「现在刷卡」
                // 余额只记录：地铁进站时弹的余额还是上一笔（公交）扣完的数，按差值判断会错位；刷卡判断看下面的弹窗原文
                TraceLog.event("tap_balance", "value" to v, "delta" to delta, "prevAgoMin" to if (prevAt > 0) (now - prevAt) / 60_000L else null)
            }
            // 余额没变也刷新「看到时间」，下一次扣费才算得上新鲜
            else p.edit().putLong("balanceAt", now).apply()
        }
    }

    private var lastTapSig = ""
    private var lastTapAt = 0L
    private var lastStaleAt = 0L

    /**
     * 系统 NFC 刷卡弹窗（只认系统界面 / 钱包）：
     * 「已出站」= 出站，「已进站」= 进站，「消费成功 本次消费：x 元」= 刚刷卡（公交上车等，由行程按当前段解释）。
     * 只认这一条记录自己的时间戳是「刚刚」的（见 TapPopup）：通知栏里几分钟前的旧扣费，哪怕旁边别的通知写着「刚刚」也不算。
     * 同一笔（种类 + 金额）90 秒内只算一次：「刚刚」最多挂一分钟，弹窗和通知栏会反复读到同一笔。
     */
    private fun tapSignal(text: String, pkg: String, now: Long) {
        if (!pkg.contains("systemui") && !pkg.contains("wallet")) return
        val hit = TapPopup.read(text)
        if (hit == null) {
            if (TapPopup.mentions(text) && now - lastStaleAt > 60_000L) {
                lastStaleAt = now
                TraceLog.event("tap_stale", "ctx" to TapPopup.firstMention(text))
                // 几分钟前没读到的那一下：交给行程补上（只补「坐完地铁还没认出出站」这种情况）
                TapPopup.staleMinutes(text)?.let { n -> CommuteService.instance?.onStaleTap(now - n * 60_000L - 30_000L) }
            }
            return
        }
        if (hit.sig == lastTapSig && now - lastTapAt < 90_000L) return
        lastTapSig = hit.sig; lastTapAt = now
        val kind = hit.kind
        TraceLog.event("tap_popup", "kind" to kind.name, "ctx" to hit.ctx)
        TransitSignal.post(this, kind, "刷卡弹窗：${kind.name}", pkg, now)
        // 行程还没开始（比如公司离车站近，还没走出范围就进站了）：刷卡就直接开始这趟
        if (CommuteService.instance == null && (kind == TransitSignal.Kind.ENTER || kind == TransitSignal.Kind.TAP)) {
            Scheduler.pendingInWindow(this)?.let { plan ->
                Diag.mark(this, "刷卡${if (kind == TransitSignal.Kind.ENTER) "进站" else "上车"}，自动开始「${plan.name}」")
                runCatching { CommuteService.start(this, plan.id, manual = false) }
            }
        }
    }

    /** 深度最多 6 层、总共最多 80 个节点 */
    private fun collect(n: AccessibilityNodeInfo, depth: Int, out: MutableSet<String>, budget: IntArray) {
        if (depth > MAX_DEPTH || budget[0] <= 0) return
        budget[0]--
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        for (i in 0 until n.childCount) {
            if (budget[0] <= 0) break
            val c = runCatching { n.getChild(i) }.getOrNull() ?: continue
            collect(c, depth + 1, out, budget)
        }
    }

    override fun onInterrupt() {}

    // ---------- 有没有正在等着开始的行程（每分钟最多查一次） ----------
    private var windowCheckedAt = 0L
    private var windowOpenCache = false
    private fun windowOpen(): Boolean {
        val now = System.currentTimeMillis()
        if (now - windowCheckedAt > 60_000L) {
            windowCheckedAt = now
            windowOpenCache = runCatching { Scheduler.pendingInWindow(this) != null }.getOrDefault(false)
        }
        return windowOpenCache
    }

    // ---------- Wi-Fi 断开：马上看一眼是不是离开了出发地（不用等下一次 2 分钟的检查） ----------
    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            val req = android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build()
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onLost(network: android.net.Network) {
                    if (CommuteService.instance != null) return
                    val plan = runCatching { Scheduler.pendingInWindow(this@TransitA11y) }.getOrNull() ?: return
                    if (plan.leaveMode()) Scheduler.watchNow(this@TransitA11y, plan)
                }
            }
            cm.registerNetworkCallback(req, cb); netCb = cb
        }
    }

    override fun onDestroy() {
        netCb?.let { cb -> runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        super.onDestroy()
    }

    companion object {
        private const val PREFS = "transit_a11y"
        private const val MAX_DEPTH = 6
        private const val MAX_NODES = 80
        private val KEYWORDS = Regex("交通卡|余额|到站|下车|进站|出站|地铁|公交|乘车|消费成功|本次消费")
        private val BALANCE = Regex("余额[:：]?\\s*¥?\\s*([0-9]+\\.[0-9]{2})")

        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, TransitA11y::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
