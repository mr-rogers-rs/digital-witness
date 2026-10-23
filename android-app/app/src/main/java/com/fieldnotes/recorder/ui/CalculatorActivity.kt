package com.fieldnotes.recorder.ui

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
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
 * Disguise activity that looks and works like a basic calculator.
 * Typing the user's own unlock code followed by `=` opens the real UI.
 *
 * The calculator is fully functional for basic arithmetic so it passes
 * casual inspection. There is no built-in code: the sequence is verified
 * against the PIN the user set during onboarding (the old hardcoded default
 * `1152` is now in [weakCodes] and rejected at setup — see hardening plan 1.1).
 * Only a digit run whose LENGTH matches a configured code is treated as an
 * unlock attempt, so ordinary arithmetic can't trip the brute-force counter.
 */
class CalculatorActivity : AppCompatActivity() {

    private lateinit var display: TextView
    private var currentInput = ""
    private var operand1: Double? = null
    private var pendingOp: String? = null
    private var justEvaluated = false

    // Track last N digits typed for secret code detection
    private var digitSequence = ""

    // Trivially guessable codes that are rejected during setup
    private val weakCodes = setOf(
        "0000", "1111", "2222", "3333", "4444", "5555", "6666", "7777",
        "8888", "9999", "1234", "4321", "0123", "3210", "1152"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        // Recent Apps shows the task label and an icon. Override both so the
        // switcher displays a generic "Calculator" entry instead of the app's
        // android:label. The actual screenshot thumbnail is suppressed by
        // FLAG_SECURE above.
        try {
            setTaskDescription(ActivityManager.TaskDescription(getString(R.string.disguise_app_name)))
        } catch (_: Exception) {}

        // First run → guided onboarding. (Disguise is opt-in and only enabled
        // post-onboarding, so this is a safety net rather than the usual path.)
        val onboardingDone = try { Prefs.isOnboardingComplete(this) } catch (_: Exception) { true }
        if (!onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }


        if (!Prefs.isUnlockCodeSet(this)) {
            showUnlockCodeSetup()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            setPadding(0, 0, 0, 0)
        }

        // Display
        display = TextView(this).apply {
            text = "0"
            textSize = 48f
            setTextColor(Color.WHITE)
            gravity = Gravity.END or Gravity.BOTTOM
            setPadding(24, 48, 24, 24)
            minHeight = 200
            maxLines = 2
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { weight = 0f }
        }
        root.addView(display)

        // Button grid
        val grid = GridLayout(this).apply {
            columnCount = 4
            rowCount = 5
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply { weight = 1f }
        }

        val buttons = arrayOf(
            arrayOf("C", "+/-", "%", "/"),
            arrayOf("7", "8", "9", "x"),
            arrayOf("4", "5", "6", "-"),
            arrayOf("1", "2", "3", "+"),
            arrayOf("0", ".", "DEL", "=")
        )

        for (row in buttons) {
            for (label in row) {
                val btn = Button(this).apply {
                    text = if (label == "DEL") "⌫" else label
                    if (label == "DEL") contentDescription = getString(R.string.calc_delete)
                    textSize = 22f
                    setTypeface(null, Typeface.NORMAL)
                    isAllCaps = false

                    when {
                        label in listOf("C", "+/-", "%") -> {
                            setBackgroundColor(Color.parseColor("#A5A5A5"))
                            setTextColor(Color.BLACK)
                        }
                        label in listOf("/", "x", "-", "+", "=") -> {
                            setBackgroundColor(Color.parseColor("#FF9500"))
                            setTextColor(Color.WHITE)
                        }
                        else -> {
                            setBackgroundColor(Color.parseColor("#333333"))
                            setTextColor(Color.WHITE)
                        }
                    }

                    val lp = GridLayout.LayoutParams().apply {
                        width = 0
                        height = 0
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                        rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                        setMargins(2, 2, 2, 2)
                    }
                    layoutParams = lp
                }

                btn.setOnClickListener { onButton(label) }
                grid.addView(btn)
            }
        }

        root.addView(grid)
        setContentView(root)
    }

    private fun onButton(label: String) {
        when (label) {
            in "0".."9" -> onDigit(label)
            "." -> onDot()
            "C" -> onClear()
            "DEL" -> onDelete()
            "+/-" -> onNegate()
            "%" -> onPercent()
            "+", "-", "x", "/" -> onOperator(label)
            "=" -> onEquals()
        }
    }

    private fun onDigit(d: String) {
        if (justEvaluated) {
            currentInput = ""
            justEvaluated = false
        }
        if (currentInput == "0") currentInput = ""
        currentInput += d
        display.text = currentInput

        digitSequence += d
        if (digitSequence.length > 16) {
            digitSequence = digitSequence.takeLast(16)
        }
    }

    private fun onDot() {
        if (justEvaluated) {
            currentInput = "0"
            justEvaluated = false
        }
        if (!currentInput.contains(".")) {
            if (currentInput.isEmpty()) currentInput = "0"
            currentInput += "."
            display.text = currentInput
        }
    }

    private fun onClear() {
        currentInput = ""
        operand1 = null
        pendingOp = null
        justEvaluated = false
        digitSequence = ""
        display.text = "0"
    }

    private fun onDelete() {
        if (currentInput.isNotEmpty()) {
            currentInput = currentInput.dropLast(1)
            display.text = if (currentInput.isEmpty()) "0" else currentInput
        }
    }

    private fun onNegate() {
        if (currentInput.isNotEmpty() && currentInput != "0") {
            currentInput = if (currentInput.startsWith("-")) {
                currentInput.drop(1)
            } else {
                "-$currentInput"
            }
            display.text = currentInput
        }
    }

    private fun onPercent() {
        val v = currentInput.toDoubleOrNull() ?: return
        currentInput = formatResult(v / 100.0)
        display.text = currentInput
    }

    private fun onOperator(op: String) {
        val v = currentInput.toDoubleOrNull()
        if (v != null) {
            if (operand1 != null && pendingOp != null) {
                operand1 = evaluate(operand1!!, v, pendingOp!!)
                display.text = formatResult(operand1!!)
            } else {
                operand1 = v
            }
        }
        pendingOp = op
        currentInput = ""
        justEvaluated = false
    }

    private fun onEquals() {
        // Only check for unlock/duress codes when there is NO pending math operation
        // AND the typed digit sequence is exactly the length of a configured code.
        // Math patterns (e.g. "12 + 34 =") have operand1 != null and pendingOp != null;
        // bare-digit patterns like "5000=" used to trip the brute-force counter (audit C4)
        // because any 4+ digit sequence was treated as an unlock attempt. Length-matching
        // against the stored code length (rather than ">=4") means ordinary calculator
        // use can never reach the unlock/duress verify path, while brute-force protection
        // is preserved for the actual code length — an attacker still has to guess the
        // exact-length sequence.
        val unlockLen = Prefs.unlockCodeLength(this)
        val duressLen = Prefs.duressCodeLength(this)
        val isCodeLength =
            (unlockLen > 0 && digitSequence.length == unlockLen) ||
            (duressLen > 0 && digitSequence.length == duressLen)
        val enteringCode =
            operand1 == null && pendingOp == null && isCodeLength

        if (enteringCode) {
            // Rate limiting
            val remaining = Prefs.unlockLockoutRemaining(this)
            if (remaining > 0) {
                digitSequence = ""
                Toast.makeText(this, getString(R.string.calc_wait_seconds, remaining), Toast.LENGTH_SHORT).show()
                return
            }

            // Check duress code FIRST — silently wipes everything
            if (Prefs.isDuressCodeSet(this) && digitSequence.length == duressLen &&
                Prefs.verifyDuressCode(this, digitSequence)) {
                Prefs.resetFailedUnlockAttempts(this)
                executeDuress()
                return
            }

            // Check for unlock code
            if (Prefs.isUnlockCodeSet(this) && digitSequence.length == unlockLen &&
                Prefs.verifyUnlockCode(this, digitSequence)) {
                Prefs.resetFailedUnlockAttempts(this)
                unlock()
                return
            }

            // Non-matching code-entry attempt. Only count it as a failed attempt
            // when the sequence length matches a configured code — a wrong-length
            // sequence is just calculator input, not an attack.
            // Ambiguity: in calculator-disguise mode a bare N-digit result
            // (N == code length) pressed with "=" is indistinguishable from a
            // wrong code, so we must NOT auto-wipe here — an innocent calculation
            // must never destroy evidence (audit C4 follow-up; user decision).
            // Keep exponential rate-limiting only: it merely slows a brute-force
            // attacker, and queued data is encrypted at rest regardless.
            // Destructive wipes remain on the explicit duress code and the honest
            // LockScreenActivity (where every entry is unambiguously a PIN try).
            Prefs.recordFailedUnlockAttempt(this)
            val lockSecs = Prefs.unlockLockoutRemaining(this)
            if (lockSecs > 0) {
                Toast.makeText(this, getString(R.string.calc_locked_seconds, lockSecs), Toast.LENGTH_LONG).show()
            }
        }

        // Normal math evaluation
        val v = currentInput.toDoubleOrNull()
        if (v != null && operand1 != null && pendingOp != null) {
            val result = evaluate(operand1!!, v, pendingOp!!)
            display.text = formatResult(result)
            currentInput = formatResult(result)
            operand1 = null
            pendingOp = null
            justEvaluated = true
        }
        digitSequence = ""
    }

    private fun evaluate(a: Double, b: Double, op: String): Double {
        return when (op) {
            "+" -> a + b
            "-" -> a - b
            "x" -> a * b
            "/" -> if (b != 0.0) a / b else Double.NaN
            else -> b
        }
    }

    private fun formatResult(v: Double): String {
        return if (v == v.toLong().toDouble() && !v.isNaN() && !v.isInfinite()) {
            v.toLong().toString()
        } else {
            v.toString()
        }
    }

    /**
     * First-launch dialog requiring the user to choose a unique unlock code.
     * The calculator is fully usable while the dialog is showing, but the
     * hidden unlock won't work until a code is set.
     */
    private fun showUnlockCodeSetup() {
        val input = EditText(this).apply {
            hint = getString(R.string.calc_code_setup_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(50, 30, 50, 30)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.calc_code_setup_title))
            .setMessage(getString(R.string.calc_code_setup_message))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.calc_code_setup_confirm), null) // override below to prevent auto-dismiss
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text.toString().trim()
                when {
                    code.length < 4 -> {
                        input.error = getString(R.string.pin_error_too_short)
                    }
                    code in weakCodes || code.toSet().size == 1 -> {
                        input.error = getString(R.string.calc_code_error_weak)
                    }
                    else -> {
                        Prefs.setUnlockCode(this, code)
                        Toast.makeText(this, getString(R.string.calc_code_set_toast), Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
            }
        }

        dialog.show()
    }

    /**
     * Duress code entered: silently destroy all data, then open the main UI
     * as if nothing exists — empty state, no recordings, no keys.
     * Must look identical to a normal unlock to an observer watching.
     */
    private fun executeDuress() {
        // No log: duress wipe must leave no trace that destruction occurred.
        // Clear local state immediately so the calculator looks normal.
        digitSequence = ""
        currentInput = ""
        display.text = "0"

        // Run the wipe to COMPLETION off the UI thread, THEN open the main UI so
        // it genuinely shows an empty state. The previous async execute()
        // returned before on-disk artifacts were deleted, so the "empty" UI
        // could briefly reflect data still present on disk and a seizure right
        // after duress entry could recover it (security review HIGH).
        val appCtx = applicationContext
        Thread {
            PanicWipe.executeBlocking(appCtx, silent = true)
            runOnUiThread { startActivity(Intent(this, MainActivity::class.java)) }
        }.apply { name = "dw-duress-wipe" }.start()
    }

    private fun unlock() {
        digitSequence = ""
        currentInput = ""
        display.text = "0"
        com.fieldnotes.recorder.util.SessionLock.markUnlocked()
        startActivity(Intent(this, MainActivity::class.java))
        // Don't finish() — keep calculator in back stack as cover
    }
}
