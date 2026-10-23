package com.fieldnotes.recorder.ui

import android.app.ActivityManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fieldnotes.recorder.R
import com.fieldnotes.recorder.util.PanicWipe
import com.fieldnotes.recorder.util.Prefs

/**
 * Non-deceptive app lock screen.
 *
 * Presents a straightforward PIN entry UI — the app looks like what it is
 * (a field recorder with a security lock), rather than disguising itself.
 *
 * Rationale: being caught with a deceptive app (fake calculator) is itself
 * incriminating in many jurisdictions. A visible security lock on a
 * legitimate recording tool is normal and expected.
 *
 * Supports:
 *   - PIN unlock (same code as the calculator disguise mode)
 *   - Duress code (silently wipes data, opens empty app)
 *   - First-launch setup
 *
 * Styling comes from the shared [Ui] kit. This screen previously hardcoded a
 * light palette (`#FAFAFA` / `#333333`) and sized the keypad in **raw pixels**
 * (`btnSize = 200`), which made it the one screen that ignored dark mode — it
 * opened as a full-brightness white panel at night, which is an operational
 * problem in the field, not just a cosmetic one — and rendered keys at wildly
 * different physical sizes per density (~50 dp on a 4x device, 200 dp on a 1x
 * one). Everything below is palette-driven and dp-based.
 */
class LockScreenActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pinDisplay: TextView
    private var enteredPin = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        try {
            setTaskDescription(ActivityManager.TaskDescription(com.fieldnotes.recorder.util.Disguise.taskLabel(this)))
        } catch (_: Exception) {}

        // First run → guided onboarding (which sets the PIN as one of its steps)
        // instead of the bare PIN-setup dialog. Defensive try so a wiped-prefs
        // state can't loop back into onboarding.
        val onboardingDone = try { Prefs.isOnboardingComplete(this) } catch (_: Exception) { true }
        if (!onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }


        if (!Prefs.isUnlockCodeSet(this)) {
            showPinSetup()
        }

        val p = Ui.palette(this)
        val (scroll, col) = Ui.screen(this)
        col.gravity = Gravity.CENTER_HORIZONTAL

        col.addView(Ui.spacer(this, 24))

        col.addView(Ui.h1(this, getString(R.string.app_name)).apply { gravity = Gravity.CENTER })
        col.addView(Ui.caption(this, getString(R.string.lock_subtitle)).apply {
            gravity = Gravity.CENTER
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(6)
        })

        pinDisplay = TextView(this).apply {
            text = ""
            textSize = 28f
            setTextColor(p.textPrimary)
            gravity = Gravity.CENTER
            letterSpacing = 0.4f
            minHeight = dp(44)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(28); bottomMargin = dp(20) }
        }
        col.addView(pinDisplay)

        col.addView(buildKeypad())

        setContentView(scroll)
    }

    // ── Keypad ───────────────────────────────────────────────────────────────

    private fun buildKeypad(): GridLayout {
        val rows = arrayOf(
            arrayOf("1", "2", "3"),
            arrayOf("4", "5", "6"),
            arrayOf("7", "8", "9"),
            arrayOf("C", "0", "OK")
        )

        return GridLayout(this).apply {
            columnCount = 3
            rowCount = rows.size
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            for (row in rows) {
                for (label in row) {
                    addView(key(label))
                }
            }
        }
    }

    /**
     * One keypad key. Width is a GridLayout column weight (so the pad divides
     * the available width evenly at any screen size) and height is dp — always
     * comfortably above the 48 dp minimum touch target.
     */
    private fun key(label: String): Button {
        val p = Ui.palette(this)
        val (bg, fg, stroke) = when (label) {
            "OK" -> Triple(p.accent, p.onAccent, null)
            "C" -> Triple(p.surfaceAlt, p.textSecondary, p.fieldStroke)
            else -> Triple(p.surface, p.textPrimary, p.divider)
        }
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 20f
            setTextColor(fg)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = keyBackground(bg, stroke)
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(64)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            }
            setOnClickListener { onPinButton(label) }
        }
    }

    /** Rounded key surface with a ripple clipped to the same shape. */
    private fun keyBackground(color: Int, strokeColor: Int?): RippleDrawable {
        val radius = dp(14)
        val base = Ui.rounded(color, radius, strokeColor, if (strokeColor != null) dp(1) else 0)
        val mask = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt()); cornerRadius = radius.toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(0x33808080), base, mask)
    }

    // ── PIN entry ────────────────────────────────────────────────────────────

    private fun onPinButton(label: String) {
        when (label) {
            "C" -> {
                enteredPin = ""
                pinDisplay.text = ""
            }
            "OK" -> {
                checkPin()
            }
            else -> {
                if (enteredPin.length < 16) {
                    enteredPin += label
                    // Show dots instead of digits for privacy
                    pinDisplay.text = "•".repeat(enteredPin.length)
                }
            }
        }
    }

    private fun checkPin() {
        // A blank OK (including a second tap after a successful unlock) is not
        // a PIN attempt. Do not hash it or advance the lockout/wipe counter.
        if (enteredPin.isEmpty()) return

        // Rate limiting: if locked out, reject immediately
        val remaining = Prefs.unlockLockoutRemaining(this)
        if (remaining > 0) {
            enteredPin = ""
            pinDisplay.text = ""
            Toast.makeText(this, getString(R.string.lock_wait_seconds, remaining), Toast.LENGTH_LONG).show()
            return
        }

        // Check duress code first
        if (Prefs.isDuressCodeSet(this) && Prefs.verifyDuressCode(this, enteredPin)) {
            Prefs.resetFailedUnlockAttempts(this)
            enteredPin = ""
            pinDisplay.text = ""
            // Run the wipe to COMPLETION off the UI thread, then open the (now
            // empty) app. The previous async execute() returned before on-disk
            // capture artifacts were deleted, so a seizure right after duress
            // entry could still recover them (security review HIGH). silent =
            // true because a duress wipe must look like an ordinary unlock.
            val appCtx = applicationContext
            Thread {
                PanicWipe.executeBlocking(appCtx, silent = true)
                runOnUiThread { startActivity(Intent(this, MainActivity::class.java)) }
            }.apply { name = "dw-duress-wipe" }.start()
            return
        }

        if (Prefs.isUnlockCodeSet(this) && Prefs.verifyUnlockCode(this, enteredPin)) {
            Prefs.resetFailedUnlockAttempts(this)
            enteredPin = ""
            pinDisplay.text = ""
            com.fieldnotes.recorder.util.SessionLock.markUnlocked()
            startActivity(Intent(this, MainActivity::class.java))
            return
        }

        // Wrong PIN — record failure and check consequences
        val delay = Prefs.recordFailedUnlockAttempt(this)
        enteredPin = ""
        pinDisplay.text = ""

        when {
            delay < 0 -> {
                Toast.makeText(this, getString(R.string.lock_threshold_wiping), Toast.LENGTH_LONG).show()
                val appCtx = applicationContext
                handler.postDelayed({
                    // Wipe to completion off the UI thread before opening the
                    // empty app, so the deletion isn't racing a seizure
                    // (security review HIGH).
                    Thread {
                        PanicWipe.executeBlocking(appCtx, silent = false)
                        runOnUiThread { startActivity(Intent(this@LockScreenActivity, MainActivity::class.java)) }
                    }.apply { name = "dw-lockout-wipe" }.start()
                }, 1500)
            }
            delay > 0 -> {
                Toast.makeText(this, getString(R.string.lock_incorrect_locked, delay), Toast.LENGTH_LONG).show()
            }
            else -> {
                val attempts = Prefs.unlockFailCount(this)
                val msg = if (attempts >= 3) getString(R.string.lock_incorrect_count, attempts)
                          else getString(R.string.lock_incorrect)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPinSetup() {
        val weakCodes = setOf(
            "0000", "1111", "2222", "3333", "4444", "5555", "6666", "7777",
            "8888", "9999", "1234", "4321", "0123", "3210"
        )

        val input = EditText(this).apply {
            hint = getString(R.string.pin_setup_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(dp(24), dp(14), dp(24), dp(14))
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.pin_setup_title))
            .setMessage(getString(R.string.pin_setup_message))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.pin_setup_confirm), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text.toString().trim()
                when {
                    code.length < 4 -> input.error = getString(R.string.pin_error_too_short)
                    code in weakCodes || code.toSet().size == 1 ->
                        input.error = getString(R.string.pin_error_weak)
                    else -> {
                        Prefs.setUnlockCode(this, code)
                        Toast.makeText(this, getString(R.string.pin_set_toast), Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }
}
