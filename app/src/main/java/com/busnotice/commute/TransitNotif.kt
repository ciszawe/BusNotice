package com.busnotice.commute

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 读别的 App 的出行通知（需要用户在系统里给「通知使用权」）：
 *  - NFC 交通卡刷卡：进站 / 出站，时间最准；
 *  - 系统自己的地铁到站、下车提醒。
 * 只看带出行关键词的通知，其余（聊天、短信等）一律不读不存。
 * 只把「进站 / 出站 / 到站」这类结论和原文存在本机，用来校准行程。
 */
class TransitNotif : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val ex = sbn.notification?.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = listOfNotNull(
            ex.getCharSequence(Notification.EXTRA_TEXT),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT),
            ex.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).joinToString(" ").trim()
        val all = "$title $text"
        val kind = TransitSignal.classify(all) ?: return
        TransitSignal.post(this, kind, all.take(120), sbn.packageName, sbn.postTime)
    }

    companion object {
        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, TransitNotif::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }
    }
}

object TransitSignal {
    enum class Kind { ENTER, EXIT, ARRIVE, APPROACH, TAP }

    private const val PREFS = "transit_signal"
    private val TRAVEL = Regex("进站|出站|交通卡|地铁|下车|到站|乘车")
    private val EXIT = Regex("已出站|出站成功|出站扣费|刷卡出站")
    private val ENTER = Regex("已进站|进站成功|刷卡进站")
    private val PROMO = Regex("奖励|领取|优惠|达标|红包|立减|福利|活动|券")
    private val ARRIVE = Regex("准备下车|当前站|即将到站|即将到达")
    // 交通卡余额 / 低额提醒：只说明刚刷了卡，由 CommuteService 按当前段解释
    private val TAP = Regex("余额|低额提醒")

    /** 只认明确的说法；对不上的出行通知只记录原文，不参与判断（以后按真实样本补规则） */
    fun classify(s: String): Kind? {
        if (!TRAVEL.containsMatchIn(s)) return null
        // 钱包/App 的营销推送（「达标奖励」「领取优惠」）不是出行事件
        if (PROMO.containsMatchIn(s)) return null
        return when {
            EXIT.containsMatchIn(s) -> Kind.EXIT
            ENTER.containsMatchIn(s) -> Kind.ENTER
            ARRIVE.containsMatchIn(s) -> Kind.ARRIVE
            TAP.containsMatchIn(s) -> Kind.TAP
            else -> Kind.APPROACH
        }
    }

    /** 最近一次出行信号（maxAgeMs 以内）；没有返回 null */
    fun recent(ctx: Context, maxAgeMs: Long): Triple<Kind, String, Long>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("at", 0L)
        if (System.currentTimeMillis() - at > maxAgeMs) return null
        val k = runCatching { Kind.valueOf(p.getString("kind", null) ?: return null) }.getOrNull() ?: return null
        return Triple(k, p.getString("text", "") ?: "", at)
    }

    fun post(ctx: Context, kind: Kind, text: String, pkg: String, at: Long) {
        // 同一条通知会反复更新：同类信号 30 秒内只算一次
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString("kind", null) == kind.name && at - p.getLong("at", 0) < 30_000) return
        p.edit().putString("kind", kind.name).putLong("at", at).putString("text", text).putString("pkg", pkg).apply()
        TraceLog.event("transit", "kind" to kind.name, "text" to text, "pkg" to pkg, "at" to at)
        val label = when (kind) { Kind.ENTER -> "进站"; Kind.EXIT -> "出站"; Kind.ARRIVE -> "到站提醒"; Kind.APPROACH -> "出行"; Kind.TAP -> "刷卡" }
        Diag.mark(ctx, "收到$label 通知：${text.take(30)}")
        // 「地铁 xxx」靠近站口这类：只记录，不切换
        if (kind != Kind.APPROACH) CommuteService.instance?.onTransitSignal(kind, text, at)
    }
}
