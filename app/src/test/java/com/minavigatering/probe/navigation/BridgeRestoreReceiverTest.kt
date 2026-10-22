package com.minavigatering.probe.navigation

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM test for the boot/update restore gate (work order W-708).
 *
 * Only the pure predicate is exercised here: the receiver's Android calls (rebind, foreground
 * START, preference write) need a device and stay unverified on this host.
 */
class BridgeRestoreReceiverTest {

    /** True only for the two allowed actions while the bridge is on. */
    private fun restores(action: String?, bridgeEnabled: Boolean): Boolean =
        shouldRestoreBridge(action, bridgeEnabled)

    @Test fun onlyBootAndPackageReplacedRestoreWhileBridgeIsOn() {
        assertTrue(restores(Intent.ACTION_BOOT_COMPLETED, true))
        assertTrue(restores(Intent.ACTION_MY_PACKAGE_REPLACED, true))
        assertFalse(restores(Intent.ACTION_BOOT_COMPLETED, false))
        assertFalse(restores(null, true))
        assertFalse(restores(null, false))
        assertFalse(restores(Intent.ACTION_SCREEN_ON, true))
        assertFalse(restores(Intent.ACTION_PACKAGE_REPLACED, true))
        assertFalse(restores("com.example.other.ACTION", true))
    }
}
