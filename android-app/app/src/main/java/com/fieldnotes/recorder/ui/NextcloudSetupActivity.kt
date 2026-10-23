package com.fieldnotes.recorder.ui

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fieldnotes.recorder.R
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.fieldnotes.recorder.cloud.NextcloudProvider
import com.fieldnotes.recorder.util.Prefs

/**
 * Guided Nextcloud connection wizard, reusable from both the first-run
 * [OnboardingActivity] and [SettingsActivity]. Collects the server URL,
 * username, and (revocable) app password, offers an inline live connection
 * test, and exposes the advanced cert-pin / chunk-size options behind a
 * disclosure. On save it persists the `nc_*` prefs and switches the upload
 * method to this provider.
 *
 * The heavy lifting (streaming chunked upload, cert pinning, assembly
 * verification) already lives in [NextcloudProvider]; this screen only drives
 * its [NextcloudProvider.authenticate] and [NextcloudProvider.fetchCertFingerprint].
 *
 * Launched with [EXTRA_ONBOARDING] = true from onboarding so it can finish back
 * into the flow (RESULT_OK) instead of standing alone.
 */
class NextcloudSetupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ONBOARDING = "onboarding"
    }

    private lateinit var urlField: EditText
    private lateinit var userField: EditText
    private lateinit var passField: EditText
    private lateinit var folderField: EditText
    private lateinit var certPinField: EditText
    private lateinit var chunkField: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (com.fieldnotes.recorder.capture.CaptureStatus.running) {
            Toast.makeText(this, getString(R.string.settings_recording_active), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        try {
            setTaskDescription(ActivityManager.TaskDescription(com.fieldnotes.recorder.util.Disguise.taskLabel(this)))
        } catch (_: Exception) {}

        val (scroll, col) = Ui.screen(this)

        col.addView(Ui.h1(this, getString(R.string.nc_title)))
        col.addView(Ui.caption(this, getString(R.string.nc_subtitle)).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(6)
        })

        col.addView(Ui.note(this, getString(R.string.nc_note)))

        // ── Connection form ──────────────────────────────────────────────
        val form = Ui.card(this)

        urlField = Ui.labeledField(this, form, getString(R.string.nc_server_url), getString(R.string.nc_server_url_hint))
        urlField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlField.setText(Prefs.ncBaseUrl(this) ?: "")

        userField = Ui.labeledField(this, form, getString(R.string.nc_username), getString(R.string.nc_username_hint))
        userField.setText(Prefs.ncUsername(this) ?: "")

        passField = Ui.labeledField(this, form, getString(R.string.nc_app_password), getString(R.string.nc_app_password_hint))
        passField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        passField.setText(Prefs.ncAppPassword(this) ?: "")

        form.addView(Ui.button(this, getString(R.string.nc_app_password_help), Ui.Btn.GHOST).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(4)
            setOnClickListener { showAppPasswordHelp() }
        })

        folderField = Ui.labeledField(this, form, getString(R.string.nc_folder), "/Documents")
        folderField.setText(Prefs.ncFolder(this))

        col.addView(form)

        // ── Advanced (collapsible) ───────────────────────────────────────
        val advToggle = Ui.button(this, getString(R.string.nc_advanced_collapsed), Ui.Btn.GHOST)
        col.addView(advToggle)

        val advCard = Ui.card(this, topMargin = 4).apply { visibility = View.GONE }
        advCard.addView(Ui.caption(this, getString(R.string.nc_pin_explain)))
        certPinField = Ui.labeledField(this, advCard, getString(R.string.nc_cert_pin), getString(R.string.nc_cert_pin_hint))
        certPinField.setText(Prefs.ncCertPin(this) ?: "")
        advCard.addView(Ui.button(this, getString(R.string.nc_fetch_fingerprint), Ui.Btn.SECONDARY).apply {
            setOnClickListener { fetchFingerprint() }
        })
        chunkField = Ui.labeledField(this, advCard, getString(R.string.nc_chunk_size), "10")
        chunkField.inputType = InputType.TYPE_CLASS_NUMBER
        chunkField.setText(Prefs.ncChunkMb(this).toString())
        col.addView(advCard)

        advToggle.setOnClickListener {
            val show = advCard.visibility != View.VISIBLE
            advCard.visibility = if (show) View.VISIBLE else View.GONE
            advToggle.text = getString(if (show) R.string.nc_advanced_expanded else R.string.nc_advanced_collapsed)
        }

        // ── Test + status ────────────────────────────────────────────────
        col.addView(Ui.button(this, getString(R.string.nc_test), Ui.Btn.SECONDARY).apply {
            setOnClickListener { testConnection() }
        })
        statusText = Ui.caption(this, "").apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(10)
        }
        col.addView(statusText)

        // ── Save ─────────────────────────────────────────────────────────
        col.addView(Ui.button(this, getString(R.string.nc_save), Ui.Btn.PRIMARY).apply {
            setOnClickListener { saveAndFinish() }
        })

        setContentView(scroll)
    }

    /** Persist the current field values so the provider (which reads Prefs) sees them. */
    private fun persistFields() {
        Prefs.setNcBaseUrl(this, urlField.text.toString().trim().ifEmpty { null })
        Prefs.setNcUsername(this, userField.text.toString().trim().ifEmpty { null })
        Prefs.setNcAppPassword(this, passField.text.toString().ifEmpty { null })
        Prefs.setNcFolder(this, folderField.text.toString().trim().ifEmpty { null })
        Prefs.setNcCertPin(this, certPinField.text.toString().trim().ifEmpty { null })
        Prefs.setNcChunkMb(this, chunkField.text.toString().toIntOrNull() ?: 10)
    }

    private fun testConnection() {
        val p = Ui.palette(this)
        persistFields()
        if (Prefs.ncBaseUrl(this).isNullOrBlank() || Prefs.ncUsername(this).isNullOrBlank() ||
            Prefs.ncAppPassword(this).isNullOrBlank()) {
            statusText.setTextColor(p.warning)
            statusText.text = getString(R.string.nc_need_fields)
            return
        }
        statusText.setTextColor(p.textSecondary)
        statusText.text = getString(R.string.nc_testing)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { NextcloudProvider(this@NextcloudSetupActivity).authenticate() }
                catch (_: Exception) { false }
            }
            if (ok) {
                statusText.setTextColor(p.success)
                statusText.text = getString(R.string.nc_connected)
            } else {
                statusText.setTextColor(p.danger)
                statusText.text = getString(R.string.nc_failed)
            }
        }
    }

    private fun fetchFingerprint() {
        persistFields()
        statusText.setTextColor(Ui.palette(this).textSecondary)
        statusText.text = getString(R.string.nc_fetching_cert)
        lifecycleScope.launch {
            val pin = withContext(Dispatchers.IO) {
                try { NextcloudProvider(this@NextcloudSetupActivity).fetchCertFingerprint() }
                catch (_: Exception) { null }
            }
            if (pin != null) {
                certPinField.setText(pin)
                statusText.text = ""
                AlertDialog.Builder(this@NextcloudSetupActivity)
                    .setTitle(getString(R.string.nc_fingerprint_title))
                    .setMessage(getString(R.string.nc_fingerprint_message, pin))
                    .setPositiveButton(getString(R.string.action_ok), null)
                    .show()
            } else {
                statusText.setTextColor(Ui.palette(this@NextcloudSetupActivity).textSecondary)
                statusText.text = getString(R.string.nc_no_cert)
            }
        }
    }

    private fun saveAndFinish() {
        persistFields()
        if (Prefs.ncBaseUrl(this).isNullOrBlank() || Prefs.ncUsername(this).isNullOrBlank() ||
            Prefs.ncAppPassword(this).isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.nc_required_fields), Toast.LENGTH_LONG).show()
            return
        }
        // Route uploads to this provider.
        Prefs.setCloudProvider(this, "nextcloud")
        Prefs.setUploadMethod(this, "cloud")
        Toast.makeText(this, getString(R.string.nc_saved), Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun showAppPasswordHelp() {
        val base = urlField.text.toString().trim().trimEnd('/')
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.nc_help_title))
            .setMessage(getString(R.string.nc_help_steps))
            .setPositiveButton(getString(R.string.action_close), null)
        if (base.isNotEmpty() && base.contains("://")) {
            builder.setNeutralButton(getString(R.string.nc_help_open_security)) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("$base/index.php/settings/user/security")))
                } catch (_: Exception) {
                    Toast.makeText(this, getString(R.string.no_browser), Toast.LENGTH_SHORT).show()
                }
            }
        }
        builder.show()
    }

    // Re-lock gate (audit H9). This screen holds the server URL, username and
    // app password, so it gates like Settings does. Just as important, it must
    // participate in the foreground counter: without it, opening this wizard
    // dropped the counter to zero and the caller (Home or Settings) re-locked
    // on return — losing unsaved Settings state. See ui/LockGate.kt.
    override fun onStart() {
        super.onStart()
        trackForegroundStart()
    }

    override fun onResume() {
        super.onResume()
        enforceSessionLock()
    }

    override fun onStop() {
        super.onStop()
        trackForegroundStop()
    }
}
