package com.fieldnotes.recorder.util

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local session lock state for the app UI (audit H9).
 *
 * Problem being solved: previously, once the user unlocked the app (via the
 * calculator disguise or the PIN lock screen), the MainActivity stayed
 * unlocked for the life of the process. Backgrounding the app and returning
 * minutes later — or a brief screen-off — did NOT re-prompt for the PIN, so
 * anyone who gained momentary access to an unlocked device could read all
 * recordings. The threat model assumes the device may be seized while
 * unlocked.
 *
 * Design:
 *  - [unlocked] is a process-local flag, set true only by a successful PIN
 *    entry (CalculatorActivity / LockScreenActivity). It is NOT persisted:
 *    a process restart always starts locked.
 *  - Foreground state is tracked with a process-wide **started-activity
 *    counter** ([foregroundActivities]), incremented by each sensitive
 *    activity's `onStart` and decremented by its `onStop`. The app is
 *    considered backgrounded only when this counter returns to 0 — i.e. the
 *    LAST visible activity stopped. This is the key difference from an
 *    earlier version that recorded `backgroundedAt` on every `onStop`,
 *    which fired on in-app navigation too (Main→Settings stopped Main and
 *    set `backgroundedAt`, so returning from Settings after >3s spuriously
 *    re-locked even though the app never left the foreground).
 *  - On resume, [needsReLock] returns true if the app was backgrounded for
 *    longer than [RELOCK_GRACE_MS] — a short grace window so brief screen
 *    rotations / permission-dialog round-trips don't force a re-lock, but
 *    any real backgrounding does.
 *  - Recording continues in the foreground service regardless of lock state
 *    (locking the phone mid-recording must not stop capture); this only
 *    gates the UI.
 *
 * Each sensitive activity calls [onActivityStarted] / [onActivityStopped]
 * from its own onStart/onStop. Only activities that gate on the lock should
 * participate; the lock activities themselves (Calculator/LockScreen) do
 * NOT, so opening them doesn't reset the background timer.
 */
object SessionLock {

    /**
     * Grace window before a background→foreground transition forces a re-lock.
     * Long enough to cover config changes, permission dialogs, and the
     * OEM-battery-settings round trip; short enough that any real
     * backgrounding re-locks.
     */
    private const val RELOCK_GRACE_MS = 3_000L

    @Volatile private var unlocked: Boolean = false
    private val backgroundedAt = AtomicLong(0L)
    private val foregroundActivities = AtomicInteger(0)

    /** Called by lock activities on successful PIN entry. */
    fun markUnlocked() {
        unlocked = true
        backgroundedAt.set(0L)
    }

    /** True if the UI is currently in the unlocked state. */
    fun isUnlocked(): Boolean = unlocked

    /**
     * Called from each sensitive (lock-gated) activity's `onStart`. Increments
     * the foreground-activity counter. We deliberately do NOT clear
     * `backgroundedAt` here: an earlier version did, on the 0→1 transition,
     * but `onStart` runs before `onResume`'s `needsReLock()` check — so the
     * timestamp would be wiped before the check could see it and re-lock would
     * never fire. Instead the grace window is evaluated purely by comparing
     * `now - backgroundedAt` in [needsReLock], and `backgroundedAt` is reset
     * only by a successful unlock ([markUnlocked]).
     */
    fun onActivityStarted() {
        foregroundActivities.incrementAndGet()
    }

    /**
     * Called from each sensitive (lock-gated) activity's `onStop`. Decrements
     * the foreground-activity counter; only when it returns to 0 — the last
     * visible activity stopped — do we record that the app went to the
     * background. A 2→1 transition (in-app navigation like Main→Settings) does
     * NOT set `backgroundedAt`, so returning to the previous activity never
     * spuriously re-locks (audit H9 fix for the navigation regression).
     */
    fun onActivityStopped() {
        if (foregroundActivities.decrementAndGet() == 0) {
            backgroundedAt.set(android.os.SystemClock.elapsedRealtime())
        }
    }

    /**
     * True if a resume should re-prompt for the PIN. Returns true when:
     *  - the session was previously unlocked, AND
     *  - the app was backgrounded for longer than the grace window (or was
     *    never unlocked this process).
     */
    fun needsReLock(): Boolean {
        if (!unlocked) return true
        val at = backgroundedAt.get()
        if (at == 0L) return false // never backgrounded since unlock
        return (android.os.SystemClock.elapsedRealtime() - at) > RELOCK_GRACE_MS
    }

    /** Force the locked state (e.g. after a duress/panic wipe). */
    fun lock() {
        unlocked = false
        backgroundedAt.set(0L)
    }
}
