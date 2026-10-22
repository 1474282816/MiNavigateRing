package com.minavigatering.probe.transport

import android.os.PowerManager
import com.minavigatering.probe.protocol.StateMessage

/**
 * Owns the probe heartbeat's single partial wakelock (work order W-733).
 *
 * Before this class the foreground service took the lock unconditionally for the whole
 * session. Now the lock is re-evaluated on every round against the accepted
 * [WakeLeasePolicy]: held while the policy reports a reason, released otherwise. The lock is
 * still bounded by [CPU_LEASE_MS] so a stalled loop cannot hold it indefinitely.
 *
 * This is the only Android file allowed to import `android.os.PowerManager`; everything the
 * decision needs arrives through [sync] as plain values.
 */
class CpuLease(private val wakeLock: PowerManager.WakeLock, private val policy: WakeLeasePolicy) {

    val isHeld: Boolean get() = wakeLock.isHeld

    /**
     * Re-evaluates the lease inside one critical section: both suppliers are read there, so
     * the policy decides on the same snapshot that the caller records for the round.
     */
    fun sync(nowElapsedMs: Long, latest: () -> StateMessage?, forced: () -> Boolean) {
        synchronized(wakeLock) {
            val hold = policy.shouldHold(nowElapsedMs, latest(), forced())
            if (hold) {
                wakeLock.acquire(CPU_LEASE_MS)
            } else if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    /** Idempotent release of this one lease; a stopped old thread must not free a new one. */
    fun release() {
        synchronized(wakeLock) {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    companion object {
        /** Bound of one acquisition; refreshed by every [sync] that holds. */
        const val CPU_LEASE_MS = 60_000L

        private const val TAG = "MiNavigateRing:probeHeartbeat"

        fun create(power: PowerManager, policy: WakeLeasePolicy): CpuLease = CpuLease(
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply { setReferenceCounted(false) },
            policy,
        )
    }
}
