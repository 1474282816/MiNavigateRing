package com.minavigatering.probe

import com.minavigatering.probe.protocol.NavProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ActionIconResTest {

    @Test
    fun allowedActionsMapToIconsAndUnknownActionsMapToNull() {
        assertEquals(14, NavProtocol.ALLOWED_ACTIONS.size)
        for (action in NavProtocol.ALLOWED_ACTIONS) {
            assertNotNull(action, actionIconRes(action))
        }
        assertNull(actionIconRes("not-an-action"))
        assertNull(actionIconRes(null))
    }
}
