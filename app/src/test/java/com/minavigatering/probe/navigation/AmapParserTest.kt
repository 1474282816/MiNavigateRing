package com.minavigatering.probe.navigation
import org.junit.Assert.*
import org.junit.Test

/** Manufactured notification text. Real Amap fixtures must be sampled separately. */
class AmapParserTest {
    private fun parse(text: String) = AmapParser.parse(AmapParser.PACKAGE, listOf(text))!!
    @Test fun turningDistanceAndRoad() {
        val i = parse("300米后右转，进入淮海中路")
        assertEquals("right", i.action); assertEquals(300, i.distanceM); assertEquals("淮海中路", i.roadName)
        assertEquals(1500, parse("1.5公里后左前方转弯").distanceM)
        assertEquals("slight-left", parse("1.5公里后左前方转弯").action)
    }
    @Test fun unrelatedAndUnsupportedAreNotStraight() {
        assertNull(AmapParser.parse("other.app", listOf("300米后右转")))
        assertNull(AmapParser.parse(AmapParser.PACKAGE, listOf("今天有雨，出行优惠")))
        assertEquals("unrecognized", parse("正在导航，重新规划路线").status)
        assertNull(parse("正在导航").action)
    }
    @Test fun endAndRoundabout() {
        assertEquals("ended", parse("导航已结束").status)
        assertEquals("roundabout-exit", parse("50米后驶出环岛，第3个出口").action)
        assertEquals(3, parse("50米后驶出环岛，第3个出口").exitNumber)
        assertEquals("uturn-right", parse("30米后向右掉头").action)
    }
    @Test fun doesNotGuessUturnSideOrAds() {
        assertEquals("unrecognized", parse("30米后掉头").status)
        assertNull(parse("30米后掉头").action)
        assertNull(AmapParser.parse(AmapParser.PACKAGE, listOf("右转领取优惠券")))
    }
    @Test fun stableTurnAndConsecutiveSameDirection() {
        val tracker = AmapTurnTracker()
        val a = tracker.actionId(parse("300米后右转，进入中山路"))
        assertEquals(a, tracker.actionId(parse("30米后右转，进入中山路")))
        assertNotEquals(a, tracker.actionId(parse("300米后右转，进入中山路")))
    }
    @Test fun consecutiveSameDirectionTurnSharesTheActionId() {
        // Manufactured notification text, not a real Amap sample: the two lines only
        // differ in distance keeping the same direction and the same road.
        // 同方向连续转弯没有通知实例 id，距离从 300 米缩到 280 米（未从≤100 米回跳）时
        // 无法与一次普通的距离更新区分；这里只断言当前信息不足、不得声称已识别新转弯。
        val tracker = AmapTurnTracker()
        val first = tracker.actionId(parse("300米后右转，进入中山路"))
        val second = tracker.actionId(parse("280米后右转，进入中山路"))
        assertEquals(
            "同方向连续转弯没有通知实例 id，距离未从≤100回跳时无法与距离更新区分，不得声称已识别新转弯",
            first, second,
        )
    }
    @Test fun removalGraceIsNotArrivalOrEnd() {
        assertEquals(5000L, AmapCloseout.REMOVAL_GRACE_MS)
        val grace = AmapCloseout.afterRemovalGrace(true)!!
        assertEquals("unrecognized", grace.status)
        assertNull(grace.action)
        assertNotEquals("ended", grace.status)
        assertNotEquals("arrive", grace.action)
        assertNull(AmapCloseout.afterRemovalGrace(false))
    }
    @Test fun disconnectAndEndCloseWithoutAction() {
        val disconnected = AmapCloseout.listenerDisconnected()
        assertEquals("unrecognized", disconnected.status)
        assertNull(disconnected.action)
        val ended = AmapCloseout.navigationEnded()
        assertEquals("ended", ended.status)
        assertNull(ended.action)
    }
}
