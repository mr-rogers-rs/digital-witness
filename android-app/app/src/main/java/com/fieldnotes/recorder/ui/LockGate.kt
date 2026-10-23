package com.fieldnotes.recorder.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import com.fieldnotes.recorder.util.Prefs
import com.fieldnotes.recorder.util.SessionLock

/**
 * Shared re-lock plumbing for lock-gated activities (audit H9).
 *
 * Two things every sensitive activity must do, previously copy-pasted per
 * screen and simply missing from three of them:
 *
 *  1. [trackForegroundStart] / [trackForegroundStop] keep [SessionLock]'s
 *     process-wide started-activity counter accurate. An activity that skips
 *     these makes the counter hit 0 during ordinary in-app navigation, so the
 *     PREVIOUS screen sees a spurious "app was backgrounded" on return and
 *     re-locks — the exact regression SessionLock's counter was introduced to
 *     fix. NextcloudSetup, Calibration and Onboarding were all missing them.
 *
 *  2. [enforceSessionLock] performs the actual gate on resume.
 *
 * Note the two are independent. Onboarding participates in the counter (so it
 * doesn't strand the screens around it) but must NOT gate: it runs before a PIN
 * exists, and bouncing to the lock screen there would redirect straight back
 * into onboarding.
 */
fun ComponentActivity.trackForegroundStart() = SessionLock.onActivityStarted()

fun ComponentActivity.trackForegroundStop() = SessionLock.onActivityStopped()

/**
 * Redirects to the lock UI if the app was backgrounded past the grace window.
 *
 * Call from `onResume`. Returns true if the activity is being torn down, in
 * which case the caller must return immediately and touch nothing else — in
 * particular it must not read or write [Prefs], since a duress/panic wipe may
 * have just destroyed the keys behind it.
 */
fun ComponentActivity.enforceSessionLock(): Boolean {

    if (!SessionLock.needsReLock()) return false
    SessionLock.lock()
    val lockIntent = if (Prefs.isCalculatorDisguiseEnabled(this)) {
        Intent(this, CalculatorActivity::class.java)
    } else {
        Intent(this, LockScreenActivity::class.java)
    }
    lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    startActivity(lockIntent)
    finish()
    return true
}
