package com.minavigatering.probe.navigation

import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NavigationUnavailableReasonTest {

    private fun state(
        id: String = "ui-1",
        navStatus: String = NavProtocol.STATUS_ACTIVE,
        action: String? = "left",
        distanceM: Int? = 32,
        roadName: String? = "\u4e2d\u5173\u6751\u5927\u8857",
    ): StateMessage = StateMessage(
        id = id,
        sentAtMs = 1L,
        navStatus = navStatus,
        action = action,
        exitNumber = null,
        distanceM = distanceM,
        roadName = roadName,
        mode = NavProtocol.MODE_WALK,
    )

    private val restoreId = NavigationSettings.RESTORE_ID_PREFIX + "1700000000000"

    private val originalWaiting = "\u8bf7\u5728\u624b\u73af\u6253\u5f00\u5bfc\u822a\u9875\uff0c\u518d\u5728\u9ad8\u5fb7\u5f00\u59cb\u5bfc\u822a\u3002"
    private val autoLaunchWaiting =
        "\u5728\u9ad8\u5fb7\u5f00\u59cb\u6b65\u884c\u6216\u9a91\u884c\u5bfc\u822a\uff0c\u624b\u73af\u5bfc\u822a\u9875\u4f1a\u81ea\u52a8\u6253\u5f00\u3002"
    private val unknownAction =
        "\u9ad8\u5fb7\u7ed9\u51fa\u7684\u8f6c\u5411\u52a8\u4f5c\u65e0\u6cd5\u8bc6\u522b\uff0c\u624b\u73af\u4e0d\u4f1a\u663e\u793a\u731c\u6d4b\u7684\u65b9\u5411\u3002"
    private val actionMissing =
        "\u9ad8\u5fb7\u6b63\u5728\u5bfc\u822a\uff0c\u4f46\u8fd9\u6761\u63d0\u793a\u8bfb\u4e0d\u51fa\u8f6c\u5411\u65b9\u5411\u3002\u624b\u73af\u4e0d\u4f1a\u663e\u793a\u731c\u6d4b\u7684\u65b9\u5411\uff0c\u8bf7\u770b\u9ad8\u5fb7\u6216\u7b49\u4e0b\u4e00\u6761\u63d0\u793a\u3002"
    private val noNotice =
        "\u6ca1\u6709\u8bfb\u5230\u9ad8\u5fb7\u7684\u5bfc\u822a\u63d0\u793a\uff1a\u5bfc\u822a\u53ef\u80fd\u5df2\u7ed3\u675f\uff0c\u6216\u9ad8\u5fb7\u5df2\u9000\u51fa\u3002\u8981\u7ee7\u7eed\u8bf7\u56de\u5230\u9ad8\u5fb7\u91cd\u65b0\u5f00\u59cb\u5bfc\u822a\u3002"

    // (a) A restored cold-start snapshot waits, and the waiting sentence follows the home switch.
    @Test
    fun restoredSnapshotWaitsAndSaysWhetherTheBandPageOpensByItself() {
        assertEquals("restore-", NavigationSettings.RESTORE_ID_PREFIX)
        val restored = state(
            id = restoreId,
            navStatus = NavProtocol.STATUS_UNRECOGNIZED,
            action = null,
            distanceM = null,
            roadName = null,
        )
        val autoOn = mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, restored, autoLaunchBand = true)
        assertEquals(NavigationPhase.WAITING, autoOn.phase)
        assertEquals("\u7b49\u5f85\u9ad8\u5fb7\u5bfc\u822a", autoOn.headline)
        assertEquals(autoLaunchWaiting, autoOn.guidance)
        assertFalse(autoOn.showAsValidInstruction)
        val autoOff = mapNavigationUi(true, true, true, true, NavProtocol.MODE_WALK, restored, autoLaunchBand = false)
        assertEquals(NavigationPhase.WAITING, autoOff.phase)
        assertEquals(originalWaiting, autoOff.guidance)
        // Priority is unchanged: a restored id never lifts ENDED or CONNECTING.
        val ended = mapNavigationUi(
            true, true, true, true, NavProtocol.MODE_WALK,
            state(id = restoreId, navStatus = NavProtocol.STATUS_ENDED),
        )
        assertEquals(NavigationPhase.ENDED, ended.phase)
        val connecting = mapNavigationUi(
            true, true, false, true, NavProtocol.MODE_WALK,
            state(id = restoreId, navStatus = NavProtocol.STATUS_UNRECOGNIZED),
        )
        assertEquals(NavigationPhase.CONNECTING, connecting.phase)
    }

    // (b) The unavailable phase names which of the three reasons applies, and only that one.
    @Test
    fun unavailableSaysWhichReasonApplies() {
        val unrecognized = state(
            navStatus = NavProtocol.STATUS_UNRECOGNIZED,
            action = null,
            distanceM = null,
            roadName = null,
        )
        val amapOn = mapNavigationUi(
            true, true, true, true, NavProtocol.MODE_WALK, unrecognized,
            amapNavigationPresent = true,
        )
        assertEquals(NavigationPhase.UNAVAILABLE, amapOn.phase)
        assertEquals("\u4fe1\u606f\u6682\u4e0d\u53ef\u7528", amapOn.headline)
        assertEquals(actionMissing, amapOn.guidance)
        val amapOff = mapNavigationUi(
            true, true, true, true, NavProtocol.MODE_WALK, unrecognized,
            amapNavigationPresent = false,
        )
        assertEquals(NavigationPhase.UNAVAILABLE, amapOff.phase)
        assertEquals("\u4fe1\u606f\u6682\u4e0d\u53ef\u7528", amapOff.headline)
        assertEquals(noNotice, amapOff.guidance)
        val mysteryAction = mapNavigationUi(
            true, true, true, true, NavProtocol.MODE_WALK,
            state(navStatus = NavProtocol.STATUS_ACTIVE, action = "mystery"),
            amapNavigationPresent = true,
        )
        assertEquals(NavigationPhase.UNAVAILABLE, mysteryAction.phase)
        assertEquals("\u4fe1\u606f\u6682\u4e0d\u53ef\u7528", mysteryAction.headline)
        assertEquals(unknownAction, mysteryAction.guidance)
        assertEquals(3, setOf(amapOn.guidance, amapOff.guidance, mysteryAction.guidance).size)
    }

    // (c) None of the new sentences carries a word the existing screen test forbids.
    @Test
    fun newSentencesCarryNoForbiddenWording() {
        val newSentences = listOf(autoLaunchWaiting, unknownAction, actionMissing, noNotice)
        val banned = listOf("left", "slight-right", "roundabout-exit", "\u4e0d\u9ed8\u8ba4\u76f4\u884c", "\u901a\u9053\u4efb\u52a1", "\u5bfc\u822a\u4e2d", "\u5df2\u786e\u8ba4", "\u5df2\u8fde\u63a5", "\u5df2\u6536\u5230")
        for (sentence in newSentences) {
            for (word in banned) {
                assertFalse("$word in $sentence", sentence.contains(word))
            }
        }
    }
}
