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
 * 带出行关键词的才记进本机行程记录。只记录，不参与任何判断。
 */
class TransitA11y : AccessibilityService() {

    private var lastText = ""
    private var lastAt = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || CommuteService.instance == null) return
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
        TraceLog.event("a11y", "pkg" to pkg, "text" to text.take(120))
        BALANCE.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let { v ->
            val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prev = p.getFloat("balance", Float.NaN)
            val delta = if (prev.isNaN()) null else Math.round((v - prev) * 100) / 100.0
            if (prev.isNaN() || delta != 0.0) {
                val prevAt = p.getLong("balanceAt", 0L)
                p.edit().putFloat("balance", v.toFloat()).putLong("balanceAt", now).apply()
                // 余额刚少了一笔车费 = 刚刷了卡。上一次余额必须是 3 小时内看到的，
                // 否则可能是之前没看到的扣费（比如打开钱包时才发现少了），不能当成「现在刷卡」
                val fresh = delta != null && delta < 0 && delta >= -15 && now - prevAt < 3 * 3_600_000L
                TraceLog.event("tap_balance", "value" to v, "delta" to delta, "fresh" to fresh)
                if (fresh) TransitSignal.post(this, TransitSignal.Kind.TAP, "交通卡扣费 ${-delta!!} 元", pkg, now)
            }
            // 余额没变也刷新「看到时间」，下一次扣费才算得上新鲜
            else p.edit().putLong("balanceAt", now).apply()
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

    companion object {
        private const val PREFS = "transit_a11y"
        private const val MAX_DEPTH = 6
        private const val MAX_NODES = 80
        private val KEYWORDS = Regex("交通卡|余额|到站|下车|进站|出站|地铁|公交|乘车")
        private val BALANCE = Regex("余额[:：]?\\s*¥?\\s*([0-9]+\\.[0-9]{2})")

        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, TransitA11y::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
