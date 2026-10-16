package com.busnotice.commute

import com.busnotice.commute.TransitSignal.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransitSignalTest {
    @Test fun tapIn() = assertEquals(Kind.ENTER, TransitSignal.classify("交通卡 已进站"))
    @Test fun tapOut() = assertEquals(Kind.EXIT, TransitSignal.classify("交通卡 已出站 扣费2元"))
    @Test fun prepareToAlight() = assertEquals(Kind.ARRIVE, TransitSignal.classify("当前站 某某站 请带好行李，准备下车"))
    // 靠近站口、还没刷卡：哪怕文案里带「进站」也不能当进站
    @Test fun approachNotEnter() = assertEquals(Kind.APPROACH, TransitSignal.classify("地铁 某某站 点击打开乘车码进站"))
    @Test fun approachPlain() = assertEquals(Kind.APPROACH, TransitSignal.classify("地铁 某某站"))
    // 公交刷卡扣费不能当地铁出站
    @Test fun busFareNotExit() = assertEquals(Kind.APPROACH, TransitSignal.classify("交通卡 公交乘车 扣费1元"))
    @Test fun chatIgnored() = assertNull(TransitSignal.classify("晚上一起吃饭吗"))
}
