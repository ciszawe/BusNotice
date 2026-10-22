package com.busnotice.commute

import com.busnotice.commute.TransitSignal.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 样本取自真机无障碍读到的文字（线路、站名、卡名已换成占位） */
class TapPopupTest {
    // 刚刷卡的单条弹窗：时间戳紧跟在后面
    @Test fun freshPopup() = assertEquals(Kind.TAP, TapPopup.read(
        "某路 · 某小区 上一班4分钟前发车 上班 · 第 1/2 段 已上车 关闭 消费成功 本次消费：2.00 元 刚刚 SIM 卡 1 今日流量超额提醒")?.kind)

    // 通知栏分组：组头写着「刚刚」，扣费记录在组里
    @Test fun freshGroup() = assertEquals(Kind.TAP, TapPopup.read(
        "某路 · 到 某小区 下一站 某路口 · 还有 14 站 结束 2 刚刚 某某交通卡低额提醒 当前余额为3.60 元，请及时充值 消费成功 本次消费：0.80 元 中国移动")?.kind)

    // 拉下通知栏：别的通知写着「刚刚」，扣费那一组是「2 分钟前」→ 旧记录
    @Test fun staleGroupWithOtherFresh() = assertNull(TapPopup.read(
        "你有38个积分待领取 已攒积分212个，领积分攒更多>> 刚刚 2 2 分钟前 某某交通卡低额提醒 当前余额为8.00 元，请及时充值 消费成功 本次消费：3.60 元 打卡 上班打卡提醒"))

    // 旧的单条扣费 + 后面别的通知是「刚刚」
    @Test fun staleSingleThenFreshChat() = assertNull(TapPopup.read(
        "消费成功 本次消费：2.00 元 5分钟前 [2条]某群: [链接] 新闻 刚刚"))

    @Test fun enterNoStamp() = assertEquals(Kind.ENTER, TapPopup.read("交通卡 已进站")?.kind)
    @Test fun exitStale() = assertNull(TapPopup.read("交通卡 已出站 12分钟前 [表情]某群 刚刚"))
    @Test fun nothing() = assertNull(TapPopup.read("晚上一起吃饭吗 刚刚"))

    // 同一笔金额作为去重签名
    @Test fun sigHasAmount() = assertEquals("TAP:2.00", TapPopup.read("消费成功 本次消费：2.00 元 刚刚")?.sig)

    // 地铁出站那下当时没读到，3 分钟后在通知栏看到
    @Test fun staleMinutesExitFare() = assertEquals(3, TapPopup.staleMinutes(
        "快跑 · 某路 某路 3 分钟到站，步行要 3 分钟，有点悬 1 分钟前 消费成功 本次消费：3.60 元 3 分钟前 2 19 分钟前 已激活VPN"))
    @Test fun staleMinutesTooOld() = assertNull(TapPopup.staleMinutes("交通卡 已出站 12分钟前 [表情]某群 刚刚"))
    @Test fun staleMinutesFresh() = assertNull(TapPopup.staleMinutes("消费成功 本次消费：0.80 元 刚刚"))
}
