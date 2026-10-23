package com.fieldnotes.recorder.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fieldnotes.recorder.R
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.fieldnotes.recorder.network.TorManager
import com.fieldnotes.recorder.util.Prefs
import com.fieldnotes.recorder.util.SessionLock

/**
 * First-run onboarding. A single activity that swaps its content per step so
 * the whole flow shares one calm surface and back-stack entry:
 *
 *   0 Welcome · 1 How it works · 2 Set PIN · 3 Permissions ·
 *   4 Recording mode · 6 Backup destination and export confidentiality ·
 *   8 Network privacy · 9 Done (internal step identifiers)
 *
 * It is the true first-run entry point: [LockScreenActivity] redirects here
 * until [Prefs.isOnboardingComplete]. The PIN step both sets the unlock code
 * and marks the session unlocked, so finishing lands the user directly in
 * [MainActivity] without an immediate re-lock.
 *
 * The network step exists because the defaults are fail-closed but silent: a fresh
 * install has `isSecureNetworkRequired = true` and `isTorEnabled = false`, so
 * a user with no VPN completes setup, records, and never uploads anything —
 * the only feedback being a generic "needs attention" notification. Onboarding
 * previously just suggested "reviewing network privacy in Settings" on the
 * final screen without saying uploads would not work until they did.
 */
class OnboardingActivity : AppCompatActivity() {

    private val weakCodes = setOf(
        "0000", "1111", "2222", "3333", "4444", "5555", "6666", "7777",
        "8888", "9999", "1234", "4321", "0123", "3210", "1152"
    )

    private val steps = listOf(0, 1, 2, 3, 4, 6, 8, 9)
    private var step = 0
    private lateinit var content: LinearLayout


    private fun capturePermissions(): Array<String> = mutableListOf(Manifest.permission.CAMERA).apply {
        if (Prefs.isAudioEnabled(this@OnboardingActivity)) add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { render() }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
                require(tree != null && tree.canRead() && tree.canWrite())
                Prefs.setCloudFolderUri(this, uri.toString())
                Prefs.setUploadMethod(this, "saf")
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.folder_access_failed), Toast.LENGTH_LONG).show()
            }
        }
        render()
    }

    private val nextcloudSetup = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        step = (savedInstanceState?.getInt("onboarding_step") ?: 0).takeIf { it in steps } ?: 0
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        try {
            setTaskDescription(ActivityManager.TaskDescription(com.fieldnotes.recorder.util.Disguise.taskLabel(this)))
        } catch (_: Exception) {}

        val (scroll, col) = Ui.screen(this)
        content = col
        setContentView(scroll)
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(Ui.sectionLabel(this, getString(R.string.ob_step_counter, steps.indexOf(step) + 1, steps.size)))
        when (step) {
            0 -> stepWelcome()
            1 -> stepHowItWorks()
            2 -> stepPin()
            3 -> stepPermissions()
            4 -> stepMode()
            6 -> stepBackup()
            8 -> stepNetwork()
            9 -> stepDone()
        }
    }

    // ── Steps ────────────────────────────────────────────────────────────────

    private fun stepWelcome() {

        content.addView(languagePicker(this))
        content.addView(Ui.h1(this, getString(R.string.app_name)))
        content.addView(bodyTop(getString(R.string.ob_welcome_body)))
        val card = Ui.card(this)
        card.addView(bullet(getString(R.string.ob_welcome_b1_t), getString(R.string.ob_welcome_b1_d)))
        card.addView(Ui.spacer(this, 10))
        card.addView(bullet(getString(R.string.ob_welcome_b2_t), getString(R.string.ob_welcome_b2_d)))
        card.addView(Ui.spacer(this, 10))
        card.addView(bullet(getString(R.string.ob_welcome_b3_t), getString(R.string.ob_welcome_b3_d)))
        content.addView(card)
        nav(next = getString(R.string.ob_get_started))
    }

    private fun stepHowItWorks() {
        content.addView(Ui.h1(this, getString(R.string.ob_how_title)))
        content.addView(bodyTop(getString(R.string.ob_how_body)))
        val card = Ui.card(this)
        card.addView(bullet(getString(R.string.ob_how_1_t), getString(R.string.ob_how_1_d)))
        card.addView(Ui.spacer(this, 10))
        card.addView(bullet(getString(R.string.ob_how_2_t), getString(R.string.ob_how_2_d)))
        card.addView(Ui.spacer(this, 10))
        card.addView(bullet(getString(R.string.ob_how_3_t), getString(R.string.ob_how_3_d)))
        card.addView(Ui.spacer(this, 10))
        card.addView(bullet(getString(R.string.ob_how_4_t), getString(R.string.ob_how_4_d)))
        content.addView(card)
        content.addView(Ui.note(this, getString(R.string.ob_how_note)))
        nav(next = getString(R.string.action_continue))
    }

    private fun stepPin() {
        content.addView(Ui.h1(this, getString(R.string.ob_pin_title)))
        content.addView(bodyTop(getString(R.string.ob_pin_body)))
        val card = Ui.card(this)
        val pin = Ui.labeledField(this, card, getString(R.string.ob_pin_label), getString(R.string.ob_pin_hint))
        pin.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val confirm = Ui.labeledField(this, card, getString(R.string.ob_pin_confirm_label), getString(R.string.ob_pin_confirm_hint))
        confirm.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        content.addView(card)
        content.addView(Ui.caption(this, getString(R.string.ob_pin_tip)).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(10)
        })
        content.addView(Ui.button(this, getString(R.string.ob_pin_button), Ui.Btn.PRIMARY).apply {
            setOnClickListener {
                val a = pin.text.toString().trim()
                val b = confirm.text.toString().trim()
                when {
                    a.length < 4 -> toast(getString(R.string.ob_pin_err_short))
                    a in weakCodes || a.toSet().size == 1 -> toast(getString(R.string.ob_pin_err_weak))
                    a != b -> toast(getString(R.string.ob_pin_err_mismatch))
                    else -> {
                        Prefs.setUnlockCode(this@OnboardingActivity, a)
                        SessionLock.markUnlocked()
                        advance()
                    }
                }
            }
        })
        if (step > 0) content.addView(backButton())
    }

    private fun stepPermissions() {
        content.addView(Ui.h1(this, getString(R.string.ob_perm_title)))
        content.addView(bodyTop(getString(R.string.ob_perm_body)))
        content.addView(Ui.checkbox(this, getString(R.string.set_enable_audio), Prefs.isAudioEnabled(this)).apply {
            setOnCheckedChangeListener { _, enabled -> Prefs.setAudioEnabled(this@OnboardingActivity, enabled); render() }
        })
        content.addView(Ui.caption(this, getString(R.string.audio_option_help)))
        val granted = hasCapturePerms()
        val card = Ui.card(this)
        card.addView(permRow(getString(R.string.perm_camera), has(Manifest.permission.CAMERA)))
        card.addView(Ui.spacer(this, 8))
        if (Prefs.isAudioEnabled(this)) card.addView(permRow(getString(R.string.perm_microphone), has(Manifest.permission.RECORD_AUDIO)))
        if (Build.VERSION.SDK_INT >= 33) {
            card.addView(Ui.spacer(this, 8))
            card.addView(permRow(getString(R.string.perm_notifications), has(Manifest.permission.POST_NOTIFICATIONS)))
        }
        content.addView(card)
        if (granted) {
            content.addView(Ui.note(this, getString(R.string.ob_perm_ok_note)))
            nav(next = getString(R.string.action_continue))
        } else {
            content.addView(Ui.button(this, getString(R.string.ob_perm_grant), Ui.Btn.PRIMARY).apply {
                setOnClickListener { requestPerms.launch(capturePermissions()) }
            })
            content.addView(Ui.button(this, getString(R.string.ob_perm_open_settings), Ui.Btn.GHOST).apply {
                setOnClickListener {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")))
                    } catch (_: Exception) {}
                }
            })
            content.addView(backButton())
        }
    }

    private fun stepMode() {
        content.addView(Ui.h1(this, getString(R.string.ob_mode_title)))
        content.addView(bodyTop(getString(R.string.ob_mode_body)))
        val current = Prefs.recordingMode(this)
        content.addView(optionCard(
            selected = current == "anonymous",
            title = getString(R.string.ob_mode_anon_title),
            desc = getString(R.string.ob_mode_anon_desc)
        ) { Prefs.setRecordingMode(this, "anonymous"); render() })
        content.addView(optionCard(
            selected = current == "evidence",
            title = getString(R.string.ob_mode_ev_title),
            desc = getString(R.string.ob_mode_ev_desc)
        ) { Prefs.setRecordingMode(this, "evidence"); render() })
        nav(next = getString(R.string.action_continue))
    }



    private fun stepBackup() {

        content.addView(Ui.h1(this, getString(R.string.ob_backup_title)))
        content.addView(bodyTop(getString(R.string.ob_backup_body)))

        val ncConfigured = !Prefs.ncBaseUrl(this).isNullOrBlank() &&
            !Prefs.ncUsername(this).isNullOrBlank() && !Prefs.ncAppPassword(this).isNullOrBlank()
        val safConfigured = Prefs.cloudFolderUri(this) != null

        content.addView(optionCard(
            selected = ncConfigured && Prefs.uploadMethod(this) == "cloud",
            title = getString(if (ncConfigured) R.string.ob_backup_nc_configured else R.string.ob_backup_nc_recommended),
            desc = getString(if (ncConfigured) R.string.ob_backup_nc_desc_review else R.string.ob_backup_nc_desc_setup)
        ) {
            nextcloudSetup.launch(Intent(this, NextcloudSetupActivity::class.java)
                .putExtra(NextcloudSetupActivity.EXTRA_ONBOARDING, true))
        })

        content.addView(optionCard(
            selected = safConfigured && Prefs.uploadMethod(this) == "saf",
            title = getString(if (safConfigured) R.string.ob_backup_saf_chosen else R.string.ob_backup_saf),
            desc = getString(R.string.ob_backup_saf_desc)
        ) { pickFolder.launch(null) })

        content.addView(Ui.note(this, getString(R.string.ob_backup_note)))

        content.addView(Ui.title(this, getString(R.string.export_title)))
        content.addView(uploadChoice(this) { render() })

        nav(next = getString(if (ncConfigured || safConfigured) R.string.action_continue else R.string.ob_skip))
    }



    private fun stepNetwork() {
        content.addView(Ui.h1(this, getString(R.string.ob_net_title)))
        content.addView(bodyTop(getString(R.string.ob_net_body)))

        val statusCard = Ui.card(this)
        statusCard.addView(Ui.sectionLabel(this, getString(R.string.ob_net_right_now)))
        val status = Ui.caption(this, getString(R.string.ob_net_checking))
        statusCard.addView(status)
        content.addView(statusCard)
        refreshNetworkStatus(status)

        val torEnabled = Prefs.isTorEnabled(this)
        val secureRequired = Prefs.isSecureNetworkRequired(this)

        content.addView(optionCard(
            selected = secureRequired && torEnabled,
            title = getString(R.string.ob_net_tor_title),
            desc = getString(R.string.ob_net_tor_desc)
        ) {
            Prefs.setTorEnabled(this, true)
            Prefs.setSecureNetworkRequired(this, true)
            render()
        })

        if (secureRequired && torEnabled) content.addView(orbotCard())

        content.addView(optionCard(
            selected = secureRequired && !torEnabled,
            title = getString(R.string.ob_net_vpn_title),
            desc = getString(R.string.ob_net_vpn_desc)
        ) {
            Prefs.setTorEnabled(this, false)
            Prefs.setSecureNetworkRequired(this, true)
            render()
        })

        content.addView(optionCard(
            selected = !secureRequired,
            title = getString(R.string.ob_net_none_title),
            desc = getString(R.string.ob_net_none_desc)
        ) {
            Prefs.setTorEnabled(this, false)
            Prefs.setSecureNetworkRequired(this, false)
            render()
        })

        content.addView(Ui.note(this, getString(R.string.ob_net_note)))

        nav(next = getString(R.string.action_continue))
    }

    /** Install/launch helper shown under the Tor option. */
    private fun orbotCard(): LinearLayout {
        val card = Ui.card(this, topMargin = 4)
        card.addView(Ui.sectionLabel(this, getString(R.string.ob_orbot)))
        if (!TorManager.isOrbotInstalled(this)) {
            card.addView(Ui.caption(this, getString(R.string.ob_orbot_missing)))
            card.addView(Ui.button(this, getString(R.string.ob_orbot_get), Ui.Btn.SECONDARY).apply {
                setOnClickListener {
                    try {
                        TorManager.getOrbotInstallIntent(this@OnboardingActivity)?.let { startActivity(it) }
                            ?: toast(getString(R.string.ob_orbot_no_store))
                    } catch (_: Exception) { toast(getString(R.string.ob_orbot_no_store)) }
                }
            })
        } else {
            card.addView(Ui.caption(this, getString(R.string.ob_orbot_installed)))
            card.addView(Ui.button(this, getString(R.string.ob_orbot_open), Ui.Btn.SECONDARY).apply {
                setOnClickListener {
                    try {
                        TorManager.getOrbotLaunchIntent(this@OnboardingActivity)?.let { startActivity(it) }
                            ?: toast(getString(R.string.ob_orbot_cant_open))
                    } catch (_: Exception) { toast(getString(R.string.ob_orbot_cant_open)) }
                }
            })
        }
        card.addView(Ui.button(this, getString(R.string.ob_orbot_recheck), Ui.Btn.GHOST).apply {
            setOnClickListener { render() }
        })
        return card
    }

    /**
     * Fills [target] with the live upload verdict. Mirrors
     * [TorManager.shouldBlockUpload] so the wording can never disagree with what
     * the uploader will actually do. The SOCKS probe blocks, so it runs on IO.
     */
    private fun refreshNetworkStatus(target: TextView) {
        val p = Ui.palette(this)
        lifecycleScope.launch {
            val ctx = this@OnboardingActivity
            val torEnabled = Prefs.isTorEnabled(ctx)
            val secureRequired = Prefs.isSecureNetworkRequired(ctx)
            val vpn = TorManager.isVpnActive(ctx)
            val torUp = if (torEnabled) withContext(Dispatchers.IO) { TorManager.isProxyReachable() } else false

            val (text, ok) = when {
                !secureRequired ->
                    getString(R.string.ob_net_status_none) to false
                torEnabled && torUp ->
                    getString(R.string.ob_net_status_tor_ok) to true
                torEnabled ->
                    getString(R.string.ob_net_status_tor_blocked) to false
                vpn ->
                    getString(R.string.ob_net_status_vpn_ok) to true
                else ->
                    getString(R.string.ob_net_status_blocked) to false
            }
            target.text = text
            target.setTextColor(if (ok) p.success else p.warning)
        }
    }

    private fun stepDone() {

        content.addView(Ui.h1(this, getString(R.string.ob_done_title)))
        content.addView(bodyTop(getString(R.string.ob_done_body)))
        val card = Ui.card(this)
        card.addView(summaryRow(getString(R.string.ob_sum_mode), getString(if (Prefs.isAnonymousMode(this)) R.string.ob_mode_anonymous_short else R.string.ob_mode_evidence_short)))
        card.addView(Ui.divider(this))
        card.addView(summaryRow(getString(R.string.bitrate_title), getString(when (Prefs.videoBitrateMode(this)) {
            "target" -> R.string.bitrate_rate
            "bpp" -> R.string.bitrate_legacy
            else -> R.string.bitrate_auto
        }, Prefs.videoTargetKbps(this))))
        card.addView(Ui.divider(this))
        val dest = when {
            !Prefs.ncBaseUrl(this).isNullOrBlank() && Prefs.uploadMethod(this) == "cloud" -> getString(R.string.backup_nextcloud)
            Prefs.cloudFolderUri(this) != null && Prefs.uploadMethod(this) == "saf" -> getString(R.string.ob_backup_saf)
            else -> getString(R.string.ob_dest_none)
        }
        card.addView(summaryRow(getString(R.string.ob_sum_backup), dest))
        card.addView(Ui.divider(this))
        card.addView(summaryRow(getString(R.string.ob_sum_uploads), getString(when {
            !Prefs.isSecureNetworkRequired(this) -> R.string.ob_uploads_any
            Prefs.isTorEnabled(this) -> R.string.ob_uploads_tor
            else -> R.string.ob_uploads_vpn
        })))
        card.addView(Ui.divider(this))
        card.addView(summaryRow(getString(R.string.export_title), Prefs.exportStatus(this)))
        card.addView(Ui.divider(this))
        card.addView(summaryRow(getString(R.string.ob_sum_applock), getString(R.string.ob_applock_pin_set)))
        content.addView(card)
        content.addView(Ui.note(this, getString(R.string.ob_done_note)))
        content.addView(Ui.button(this, getString(R.string.ob_start, getString(R.string.app_name)), Ui.Btn.PRIMARY).apply {
            setOnClickListener {
                finishSetup()
            }
        })
    }

    // ── Building blocks ────────────────────────────────────────────────────────

    private fun finishSetup() {


        Prefs.setOnboardingComplete(this, true)
        com.fieldnotes.recorder.upload.UploadScheduler.ensurePeriodicDrain(this)
        com.fieldnotes.recorder.upload.UploadScheduler.retryNow(this)
        SessionLock.markUnlocked()
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun advance() { steps.getOrNull(steps.indexOf(step) + 1)?.let { step = it; render() } }
    private fun back() { steps.getOrNull(steps.indexOf(step) - 1)?.let { step = it; render() } }

    private fun nav(next: String) {
        content.addView(Ui.button(this, next, Ui.Btn.PRIMARY).apply {
            setOnClickListener { advance() }
        })
        if (step > 0) content.addView(backButton())
    }

    private fun backButton(): View =
        Ui.button(this, getString(R.string.action_back), Ui.Btn.GHOST).apply { setOnClickListener { back() } }

    private fun bodyTop(text: CharSequence): TextView =
        Ui.body(this, text).apply {
            (layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(ViewGroupMatch(), ViewGroupWrap()))
                .also { it.topMargin = dp(8); layoutParams = it }
        }

    private fun bullet(title: CharSequence, desc: CharSequence): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.title(this@OnboardingActivity, title))
            addView(Ui.caption(this@OnboardingActivity, desc).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(2)
            })
        }

    private fun summaryRow(label: CharSequence, value: CharSequence): LinearLayout {
        val p = Ui.palette(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@OnboardingActivity).apply {
                text = label; textSize = 15f; setTextColor(p.textSecondary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@OnboardingActivity).apply {
                text = value; textSize = 15f; setTextColor(p.textPrimary)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
        }
    }

    private fun permRow(label: String, granted: Boolean): LinearLayout {
        val p = Ui.palette(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@OnboardingActivity).apply {
                text = label; textSize = 16f; setTextColor(p.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@OnboardingActivity).apply {
                text = getString(if (granted) R.string.perm_granted else R.string.perm_needed)
                textSize = 14f
                setTextColor(if (granted) p.success else p.textSecondary)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
        }
    }

    private fun optionCard(selected: Boolean, title: CharSequence, desc: CharSequence, onClick: () -> Unit): LinearLayout {
        val p = Ui.palette(this)
        return Ui.card(this).apply {
            background = Ui.rounded(
                if (selected) p.accentSoft else p.surface,
                dp(16),
                if (selected) p.accent else p.divider,
                dp(if (selected) 2 else 1)
            )
            addView(Ui.title(this@OnboardingActivity, title))
            addView(Ui.caption(this@OnboardingActivity, desc).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(4)
            })
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun has(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun hasCapturePerms(): Boolean =
        has(Manifest.permission.CAMERA) && (!Prefs.isAudioEnabled(this) || has(Manifest.permission.RECORD_AUDIO))

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun ViewGroupMatch() = LinearLayout.LayoutParams.MATCH_PARENT
    private fun ViewGroupWrap() = LinearLayout.LayoutParams.WRAP_CONTENT

    // Foreground tracking only — deliberately NO re-lock gate (audit H9).
    // Onboarding runs before a PIN exists, and the lock screen redirects back
    // here until onboarding completes, so gating would loop. But it must still
    // hold the counter: the Nextcloud step launches a second activity, and
    // without this the counter hit zero and that screen re-locked on return.
    // See ui/LockGate.kt.
    override fun onStart() {
        super.onStart()
        trackForegroundStart()
    }

    /**
     * Steps 3 (permissions) and 6 (network) show state the user changes OUTSIDE
     * this app — the system permission screen, Orbot, a VPN client — so they go
     * stale the moment the user leaves. Re-render just those two on return.
     * Deliberately not the others: step 2 holds a half-typed PIN that a blind
     * re-render would wipe.
     */
    override fun onResume() {
        super.onResume()
        if (step == 3 || step == 7) render()
    }

    override fun onStop() {
        super.onStop()
        trackForegroundStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("onboarding_step", step)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (step > 0) back() else super.onBackPressed()
    }
}
