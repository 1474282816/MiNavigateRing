package com.minavigatering.probe.transport

import com.minavigatering.probe.transport.WearAutoLaunchPolicy.Companion.ALREADY_LAUNCHED
import com.minavigatering.probe.transport.WearAutoLaunchPolicy.Companion.BRIDGE_OFF
import com.minavigatering.probe.transport.WearAutoLaunchPolicy.Companion.NO_NODE
import com.minavigatering.probe.transport.WearAutoLaunchPolicy.Companion.PAGE_ALIVE
import com.minavigatering.probe.transport.WearAutoLaunchPolicy.Companion.PAGE_ALIVE_WINDOW_MS
import com.minavigatering.probe.transport.WearAutoLaunchPolicy.Companion.SETTING_OFF
import com.minavigatering.probe.transport.WearAutoLaunchDecision.Launch
import com.minavigatering.probe.transport.WearAutoLaunchDecision.Skip
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host JVM test for the auto-launch policy (work order W-741).
 *
 * Every input below is built in this file: these are synthetic fixtures, not device readings, and
 * they prove nothing about a real band launch. Only the decision order, the session record and the
 * PAGE_ALIVE window boundary are pinned.
 */
class WearAutoLaunchPolicyTest {

    @Test
    fun `one launch per session, and onNavigationEnded reopens the session`() {
        val policy = WearAutoLaunchPolicy()

        assertLaunch(policy.onNavigationStarted(FULL_SATISFIED), "the first fully satisfied signal launches")
        assertSkip(
            ALREADY_LAUNCHED,
            policy.onNavigationStarted(FULL_SATISFIED),
            "a second signal in the same session must not launch again",
        )

        policy.onNavigationEnded()
        assertLaunch(policy.onNavigationStarted(FULL_SATISFIED), "a new session may launch again")
    }

    @Test
    fun `five skips fire in the fixed order and never record a launch`() {
        val policy = WearAutoLaunchPolicy()

        // SETTING_OFF first: every other condition fails here too.
        assertSkip(SETTING_OFF, policy.onNavigationStarted(WearAutoLaunchInput(false, false, false, null)))
        assertLaunch(policy.onNavigationStarted(FULL_SATISFIED), "SETTING_OFF must not record a launch")
        policy.onNavigationEnded()

        // BRIDGE_OFF with the node missing as well: the bridge check runs before the node check.
        assertSkip(BRIDGE_OFF, policy.onNavigationStarted(WearAutoLaunchInput(true, false, false, null)))
        assertLaunch(policy.onNavigationStarted(FULL_SATISFIED), "BRIDGE_OFF must not record a launch")
        policy.onNavigationEnded()

        assertSkip(NO_NODE, policy.onNavigationStarted(WearAutoLaunchInput(true, true, false, null)))
        assertLaunch(policy.onNavigationStarted(FULL_SATISFIED), "NO_NODE must not record a launch")
        policy.onNavigationEnded()

        // A fresh ack (age 0) means the page is answering, so there is nothing to launch.
        assertSkip(PAGE_ALIVE, policy.onNavigationStarted(WearAutoLaunchInput(true, true, true, 0L)))
        assertLaunch(policy.onNavigationStarted(FULL_SATISFIED), "PAGE_ALIVE must not record a launch")

        assertSkip(ALREADY_LAUNCHED, policy.onNavigationStarted(FULL_SATISFIED))
        assertSkip(
            PAGE_ALIVE,
            policy.onNavigationStarted(WearAutoLaunchInput(true, true, true, 0L)),
            "PAGE_ALIVE is checked before ALREADY_LAUNCHED even after a launch",
        )

        policy.onNavigationEnded()
        assertLaunch(policy.onNavigationStarted(FULL_SATISFIED), "onNavigationEnded clears the launch record")
    }

    @Test
    fun `PAGE_ALIVE boundary is inclusive at the window, exclusive just past it, and null is no answer`() {
        val policy = WearAutoLaunchPolicy()

        val exactlyAtWindow = WearAutoLaunchInput(true, true, true, PAGE_ALIVE_WINDOW_MS)
        assertSkip(PAGE_ALIVE, policy.onNavigationStarted(exactlyAtWindow), "an ack exactly at the window still counts")

        val justPastWindow = WearAutoLaunchInput(true, true, true, PAGE_ALIVE_WINDOW_MS + 1)
        assertLaunch(
            policy.onNavigationStarted(justPastWindow),
            "one millisecond past the window is no longer answering, and the boundary skip recorded no launch",
        )

        policy.onNavigationEnded()
        assertLaunch(
            policy.onNavigationStarted(WearAutoLaunchInput(true, true, true, null)),
            "no ack at all is not an answer",
        )
    }

    private fun assertLaunch(decision: WearAutoLaunchDecision, message: String) {
        assertEquals(message, Launch, decision)
    }

    private fun assertSkip(reason: String, decision: WearAutoLaunchDecision, message: String? = null) {
        assertEquals(message ?: "expected Skip($reason)", Skip(reason), decision)
    }

    private companion object {
        /** Every condition satisfied and no ack seen: the only input that may launch. */
        val FULL_SATISFIED = WearAutoLaunchInput(true, true, true, null)
    }
}
