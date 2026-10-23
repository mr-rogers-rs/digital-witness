package com.fieldnotes.recorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.fieldnotes.recorder.R
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.fieldnotes.recorder.network.TorManager
import com.fieldnotes.recorder.util.Prefs
import com.fieldnotes.recorder.cloud.CloudUploadManager
import com.fieldnotes.recorder.cloud.NextcloudProvider
import com.fieldnotes.recorder.upload.UploadQueue

/**
 * Capability-derived recording choices, with advanced camera/encoding controls collapsed.
 */
class SettingsActivity : AppCompatActivity() {

    private var gpsCheckbox: CheckBox? = null
    private var refreshCameraSummary: (() -> Unit)? = null
    private var refreshBatteryStatus: (() -> Unit)? = null
    private var refreshElectionConnection: (() -> Unit)? = null

    private val requestLocationPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            gpsCheckbox?.isChecked = true
        } else {
            gpsCheckbox?.isChecked = false
            Toast.makeText(this, getString(R.string.set_location_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
                require(tree != null && tree.canRead() && tree.canWrite())
                Prefs.setCloudFolderUri(this, uri.toString())
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.folder_access_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

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

        val p = Ui.palette(this)
        val (scrollView, root) = Ui.screen(this)

        root.addView(Ui.h1(this, getString(R.string.set_title)))
        val languageOptions = languagePicker(this).apply {
            addView(Ui.caption(this@SettingsActivity, getString(R.string.language_change_note)))
        }
        root.addView(languageOptions)

        // Helper: a titled card added to the root, returning it for content.
        fun section(title: String, parent: LinearLayout = root): LinearLayout {
            val c = Ui.card(this)
            c.addView(Ui.title(this, title))
            c.addView(Ui.spacer(this, 4))
            parent.addView(c)
            return c
        }
        fun label(text: String): TextView = Ui.caption(this, text).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12)
        }
        fun number(field: EditText): EditText = field.apply { inputType = InputType.TYPE_CLASS_NUMBER }

        fun details(parent: LinearLayout, title: String): LinearLayout {
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            parent.addView(Ui.button(this, title, Ui.Btn.SECONDARY).apply {
                setOnClickListener {
                    body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    text = if (body.visibility == View.VISIBLE) getString(R.string.settings_hide_details, title) else title
                }
            })
            parent.addView(body)
            return body
        }
        // Attach this card after the everyday settings. Hidden values still keep
        // their saved state and are validated by the same Save handler.
        val advancedCard = Ui.card(this)
        val advancedBody = details(advancedCard, getString(R.string.settings_advanced))
        val exportCard = section(getString(R.string.export_title), advancedBody)
        val exportGroup = RadioGroup(this)
        val exportEncrypted = Ui.radio(this, getString(R.string.export_encrypted))
        val exportPlain = Ui.radio(this, getString(R.string.export_plaintext))
        exportGroup.addView(exportPlain)
        exportGroup.addView(exportEncrypted)
        exportCard.addView(exportGroup)
        when (Prefs.exportMode(this)) {
            "encrypted" -> exportGroup.check(exportEncrypted.id)
            "plaintext" -> exportGroup.check(exportPlain.id)
        }
        exportCard.addView(Ui.caption(this, getString(R.string.export_explanation)))
        val recipientKey = Ui.field(this, getString(R.string.export_key_hint)).apply {
            setSingleLine(false)
            minLines = 3
            maxLines = 8
            setText(Prefs.recipientPublicKey(this@SettingsActivity))
        }
        exportCard.addView(recipientKey)
        fun updateRecipientVisibility() { recipientKey.visibility = if (exportEncrypted.isChecked) View.VISIBLE else View.GONE }
        exportGroup.setOnCheckedChangeListener { _, _ -> updateRecipientVisibility() }
        updateRecipientVisibility()
        exportCard.addView(Ui.caption(this, getString(R.string.export_existing_policy)))

        // Recording choices first; camera implementation details stay collapsed.
        val qualityCard = section(getString(R.string.set_quality))
        val cameraCard = section(getString(R.string.camera_view_title))
        val backendCard = details(cameraCard, getString(R.string.camera_backend_title))
        val backendGroup = RadioGroup(this)
        val camera2 = Ui.radio(this, getString(R.string.camera_backend_current))
        val cameraX = Ui.radio(this, getString(R.string.camera_backend_experimental))
        backendGroup.addView(camera2)
        backendGroup.addView(cameraX)
        backendGroup.check(if (Prefs.cameraBackend(this) == "camerax") cameraX.id else camera2.id)
        backendCard.addView(backendGroup)
        backendCard.addView(Ui.caption(this, getString(R.string.camera_backend_help)))
        backendCard.addView(Ui.button(this, getString(R.string.measurement_title), Ui.Btn.SECONDARY).apply {
            setOnClickListener {
                lifecycleScope.launch {
                    val report = withContext(Dispatchers.IO) {
                        com.fieldnotes.recorder.capture.RecordingMeasurements.report(this@SettingsActivity)
                    }
                    val dialog = AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(R.string.measurement_title)
                        .setMessage(report + "\n\n" + getString(R.string.measurement_help))
                        .setPositiveButton(android.R.string.ok, null).create()
                    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    dialog.show()
                }
            }
        })
        val cameraSummary = Ui.caption(this, "").also { cameraCard.addView(it) }
        cameraCard.addView(Ui.caption(this, getString(R.string.calib_settings_help)))
        cameraCard.addView(Ui.button(this, getString(R.string.set_calibrate), Ui.Btn.SECONDARY).apply {
            setOnClickListener { startActivity(Intent(this@SettingsActivity, CalibrationActivity::class.java)) }
        })

        qualityCard.addView(label(getString(R.string.set_fps)))
        val fpsSpinner = Spinner(this).also { qualityCard.addView(it) }
        val ratesHelp = Ui.caption(this, getString(R.string.native_rates_simple)).also { qualityCard.addView(it) }
        var nativeRates = emptyList<Int>()
        var normalRate = 0
        var initializedRates = false
        refreshCameraSummary = {
            val backend = if (cameraX.isChecked) "camerax" else "camera2"
            val previous = if (initializedRates) nativeRates.getOrNull(fpsSpinner.selectedItemPosition) else
                when (Prefs.videoPreset(this)) { "battery" -> 15; "minimum" -> 10; "custom" -> Prefs.videoFps(this); else -> null }
            val reference = runCatching { com.fieldnotes.recorder.util.CameraUtil.normalVideoResolution(this, backend) }.getOrNull()
            val selected = if (backend == Prefs.cameraBackend(this))
                runCatching { com.fieldnotes.recorder.util.CameraUtil.getOptimalVideoResolution(this, 0) }.getOrNull() else reference
            nativeRates = if (reference == null || selected == null) emptyList() else runCatching {
                com.fieldnotes.recorder.util.CameraUtil.nativeFpsOptions(this, reference, backend)
                    .intersect(com.fieldnotes.recorder.util.CameraUtil.nativeFpsOptions(this, selected, backend).toSet()).sortedDescending()
            }.getOrDefault(emptyList())
            normalRate = nativeRates.maxOrNull() ?: 0
            fpsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, nativeRates.map { rate ->
                val value = com.fieldnotes.recorder.util.CameraUtil.fpsLabel(this, rate)
                if (rate == normalRate) getString(R.string.native_rate_normal, value) else value
            }).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            fpsSpinner.setSelection(nativeRates.indexOf(previous).coerceAtLeast(0))
            fpsSpinner.isEnabled = nativeRates.isNotEmpty()
            ratesHelp.text = getString(if (nativeRates.isEmpty()) R.string.fail_full_view else R.string.native_rates_simple)
            cameraSummary.text = if (selected == null) getString(R.string.fail_full_view) else
                getString(R.string.calib_default_size, selected.first, selected.second)
            initializedRates = true
        }
        backendGroup.setOnCheckedChangeListener { _, _ -> refreshCameraSummary?.invoke() }
        refreshCameraSummary?.invoke()
        refreshBatteryStatus?.invoke()
        val bitrateChoice = BitrateChoice(this).also { qualityCard.addView(it) }
        val adaptiveBitrate = CheckBox(this).apply {
            text = getString(R.string.adaptive_bitrate_setting)
            isChecked = Prefs.adaptiveVideoBitrate(this@SettingsActivity)
        }.also { qualityCard.addView(it) }
        val customLayout = details(qualityCard, getString(R.string.settings_advanced_video))
        customLayout.addView(Ui.caption(this, getString(R.string.settings_audio_unchanged)))
        customLayout.addView(label(getString(R.string.set_gop)))
        val gopEdit = Ui.field(this, getString(R.string.set_gop_hint)).also {
            number(it); it.setText(Prefs.videoGopSeconds(this).toString()); customLayout.addView(it)
        }

        // === Recording ===
        val recordingCard = section(getString(R.string.set_recording))
        val video = Ui.checkbox(this, getString(R.string.set_enable_video), Prefs.isVideoEnabled(this)).also { recordingCard.addView(it) }
        val audio = Ui.checkbox(this, getString(R.string.set_enable_audio), Prefs.isAudioEnabled(this)).also { recordingCard.addView(it) }
        recordingCard.addView(Ui.caption(this, getString(R.string.audio_option_help)))
        val wakelock = Ui.checkbox(this, getString(R.string.set_wakelock), Prefs.isWakelockEnabled(this)).also { recordingCard.addView(it) }
        recordingCard.addView(label(getString(R.string.set_segment_len)))
        val segmentSecs = Ui.field(this, getString(R.string.set_segment_len_hint)).also { number(it); it.setText(Prefs.segmentDurationSeconds(this).toString()); recordingCard.addView(it) }

        // === Upload Method ===
        val uploadCard = section(getString(R.string.set_backup))
        val uploadMethodGroup = RadioGroup(this)
        val uploadMethodSaf = Ui.radio(this, getString(R.string.set_method_saf))
        val uploadMethodCloud = Ui.radio(this, getString(R.string.set_method_cloud))
        uploadMethodGroup.addView(uploadMethodSaf)
        uploadMethodGroup.addView(uploadMethodCloud)
        when (Prefs.uploadMethod(this)) {
            "saf" -> uploadMethodGroup.check(uploadMethodSaf.id)
            "cloud" -> uploadMethodGroup.check(uploadMethodCloud.id)
        }
        uploadCard.addView(uploadMethodGroup)
        uploadCard.addView(Ui.caption(this, getString(R.string.evidence_folder_help)))

        // SAF settings
        val safLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chooseCloud = Ui.button(this, getString(R.string.set_choose_folder), Ui.Btn.SECONDARY)
        val safStatus = Ui.caption(this, getString(if (Prefs.cloudFolderUri(this) != null) R.string.set_folder_configured else R.string.set_folder_none))
        safLayout.addView(chooseCloud)
        safLayout.addView(safStatus)
        safLayout.addView(Ui.caption(this, getString(R.string.ob_backup_saf_desc)))
        uploadCard.addView(safLayout)

        // Cloud provider settings
        val cloudLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cloudLayout.addView(label(getString(R.string.set_cloud_provider)))
        val cloudProviderSpinner = Spinner(this).also {
            it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
                arrayOf(getString(R.string.set_provider_dropbox), getString(R.string.set_provider_gdrive),
                    getString(R.string.set_provider_webdav), getString(R.string.set_provider_nextcloud))).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            it.setSelection(when (Prefs.cloudProvider(this)) { "dropbox" -> 0; "google_drive" -> 1; "webdav" -> 2; "nextcloud" -> 3; else -> 0 })
            cloudLayout.addView(it)
        }

        val cloudJurisdictionWarning = Ui.caption(this, "").apply { setTextColor(p.warning) }
        fun updateJurisdictionWarning() {
            cloudJurisdictionWarning.text = when (cloudProviderSpinner.selectedItemPosition) {
                0 -> getString(R.string.set_juris_dropbox)
                1 -> getString(R.string.set_juris_gdrive)
                2 -> getString(R.string.set_juris_webdav)
                3 -> getString(R.string.set_juris_nextcloud)
                else -> ""
            }
        }
        updateJurisdictionWarning()
        cloudLayout.addView(cloudJurisdictionWarning)

        // OAuth (Dropbox / Google Drive)
        val oauthLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        oauthLayout.addView(label(getString(R.string.set_access_token)))
        val accessTokenEdit = Ui.field(this, getString(R.string.set_access_token_hint)).also { it.setSingleLine(false); it.maxLines = 3; it.setText(Prefs.cloudAccessToken(this) ?: ""); oauthLayout.addView(it) }
        oauthLayout.addView(label(getString(R.string.set_dropbox_key)))
        val appKeyEdit = Ui.field(this, getString(R.string.set_dropbox_key_hint)).also { it.setText(Prefs.cloudAppKey(this) ?: ""); oauthLayout.addView(it) }
        oauthLayout.addView(label(getString(R.string.set_dropbox_refresh)))
        val refreshTokenEdit = Ui.field(this, getString(R.string.set_dropbox_refresh_hint)).also { it.setSingleLine(false); it.maxLines = 3; it.setText(Prefs.cloudRefreshToken(this) ?: ""); oauthLayout.addView(it) }
        oauthLayout.addView(Ui.caption(this, getString(R.string.set_token_note)))
        val authorizeDropbox = Ui.button(this, getString(R.string.dropbox_connect_browser), Ui.Btn.PRIMARY).apply {
            setOnClickListener {
                DropboxAuthorization.show(this@SettingsActivity, appKeyEdit.text.toString().trim()) { access, refresh ->
                    accessTokenEdit.setText(access)
                    refreshTokenEdit.setText(refresh)
                }
            }
        }
        oauthLayout.addView(authorizeDropbox)


        // Generic WebDAV credentials
        val credLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        credLayout.addView(label(getString(R.string.set_webdav_url)))
        val webdavUrlEdit = Ui.field(this, getString(R.string.set_webdav_url_hint)).also { it.setText(Prefs.cloudWebdavUrl(this) ?: ""); credLayout.addView(it) }
        credLayout.addView(label(getString(R.string.set_username)))
        val usernameEdit = Ui.field(this, getString(R.string.set_username)).also { it.setText(Prefs.cloudUsername(this) ?: ""); credLayout.addView(it) }
        credLayout.addView(label(getString(R.string.set_password)))
        val passwordEdit = Ui.field(this, getString(R.string.set_password)).also {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            it.setText(Prefs.cloudPassword(this) ?: ""); credLayout.addView(it)
        }

        // Dedicated Nextcloud
        val ncLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ncGuided = Ui.button(this, getString(R.string.set_nc_guided), Ui.Btn.PRIMARY)
        ncLayout.addView(ncGuided)
        ncLayout.addView(label(getString(R.string.nc_server_url)))
        val ncUrlEdit = Ui.field(this, getString(R.string.nc_server_url_hint)).also { it.setText(Prefs.ncBaseUrl(this) ?: ""); ncLayout.addView(it) }
        ncLayout.addView(label(getString(R.string.set_username)))
        val ncUserEdit = Ui.field(this, getString(R.string.nc_username_hint)).also { it.setText(Prefs.ncUsername(this) ?: ""); ncLayout.addView(it) }
        ncLayout.addView(label(getString(R.string.nc_app_password)))
        val ncPassEdit = Ui.field(this, getString(R.string.nc_app_password_hint)).also {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            it.setText(Prefs.ncAppPassword(this) ?: ""); ncLayout.addView(it)
        }
        ncLayout.addView(label(getString(R.string.nc_folder)))
        val ncFolderEdit = Ui.field(this, "/Documents").also { it.setText(Prefs.ncFolder(this)); ncLayout.addView(it) }
        ncLayout.addView(Ui.sectionLabel(this, getString(R.string.set_advanced_optional)))
        ncLayout.addView(label(getString(R.string.nc_cert_pin)))
        val ncCertPinEdit = Ui.field(this, getString(R.string.set_cert_pin_hint)).also { it.setText(Prefs.ncCertPin(this) ?: ""); ncLayout.addView(it) }
        val ncFetchPinButton = Ui.button(this, getString(R.string.nc_fetch_fingerprint), Ui.Btn.SECONDARY).also { ncLayout.addView(it) }
        ncLayout.addView(label(getString(R.string.nc_chunk_size)))
        val ncChunkEdit = Ui.field(this, "10").also { number(it); it.setText(Prefs.ncChunkMb(this).toString()); ncLayout.addView(it) }
        val nextcloudSetupResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                ncUrlEdit.setText(Prefs.ncBaseUrl(this) ?: "")
                ncUserEdit.setText(Prefs.ncUsername(this) ?: "")
                ncPassEdit.setText(Prefs.ncAppPassword(this) ?: "")
                ncFolderEdit.setText(Prefs.ncFolder(this))
                ncCertPinEdit.setText(Prefs.ncCertPin(this) ?: "")
                ncChunkEdit.setText(Prefs.ncChunkMb(this).toString())
            }
        }
        ncGuided.setOnClickListener { nextcloudSetupResult.launch(Intent(this, NextcloudSetupActivity::class.java)) }


        ncFetchPinButton.setOnClickListener {
            Prefs.setNcBaseUrl(this, ncUrlEdit.text.toString().trim().ifEmpty { null })
            Prefs.setNcUsername(this, ncUserEdit.text.toString().trim().ifEmpty { null })
            Prefs.setNcAppPassword(this, ncPassEdit.text.toString().ifEmpty { null })
            ncFetchPinButton.isEnabled = false
            lifecycleScope.launch {
                val pin = withContext(Dispatchers.IO) { NextcloudProvider(this@SettingsActivity).fetchCertFingerprint() }
                ncFetchPinButton.isEnabled = true
                if (pin != null) {
                    ncCertPinEdit.setText(pin)
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(getString(R.string.nc_fingerprint_title))
                        .setMessage(getString(R.string.set_fingerprint_msg, pin))
                        .setPositiveButton(getString(R.string.action_ok), null)
                        .show()
                } else {
                    Toast.makeText(this@SettingsActivity, getString(R.string.set_no_cert), Toast.LENGTH_LONG).show()
                }
            }
        }

        // Generic folder path (non-Nextcloud)
        val folderPathLabel = label(getString(R.string.nc_folder))
        val folderPathEdit = Ui.field(this, "/Backups").also { it.setText(Prefs.cloudFolderPath(this)) }

        cloudLayout.addView(oauthLayout)
        cloudLayout.addView(credLayout)
        cloudLayout.addView(ncLayout)
        cloudLayout.addView(folderPathLabel)
        cloudLayout.addView(folderPathEdit)

        val helpButton = Ui.button(this, getString(R.string.set_help_button), Ui.Btn.GHOST)
        val testButton = Ui.button(this, getString(R.string.set_test_cloud), Ui.Btn.SECONDARY)
        val testResult = Ui.caption(this, "")
        cloudLayout.addView(helpButton)
        cloudLayout.addView(testButton)
        cloudLayout.addView(testResult)

        helpButton.setOnClickListener {
            val provider = when (cloudProviderSpinner.selectedItemPosition) {
                0 -> "dropbox"; 1 -> "google_drive"; 2 -> "webdav"; 3 -> "nextcloud"; else -> "dropbox"
            }
            showCloudSetupHelp(provider)
        }
        uploadCard.addView(cloudLayout)

        fun updateCloudProviderFields() {
            val pos = cloudProviderSpinner.selectedItemPosition
            val isOAuth = pos in listOf(0, 1)
            authorizeDropbox.visibility = if (pos == 0) View.VISIBLE else View.GONE
            val isNextcloud = pos == 3
            val isWebdav = pos == 2
            oauthLayout.visibility = if (isOAuth) View.VISIBLE else View.GONE
            credLayout.visibility = if (isWebdav) View.VISIBLE else View.GONE
            ncLayout.visibility = if (isNextcloud) View.VISIBLE else View.GONE
            folderPathLabel.visibility = if (isNextcloud) View.GONE else View.VISIBLE
            folderPathEdit.visibility = if (isNextcloud) View.GONE else View.VISIBLE
        }
        var lastOAuthProvider = Prefs.cloudProvider(this)
        cloudProviderSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = when (position) { 0 -> "dropbox"; 1 -> "google_drive"; 2 -> "webdav"; else -> "nextcloud" }
                if (selected != lastOAuthProvider) {
                    accessTokenEdit.setText(Prefs.cloudCredentialFor(this@SettingsActivity, selected, "access") ?: "")
                    refreshTokenEdit.setText(Prefs.cloudCredentialFor(this@SettingsActivity, selected, "refresh") ?: "")
                    appKeyEdit.setText(Prefs.cloudCredentialFor(this@SettingsActivity, selected, "app") ?: "")
                    lastOAuthProvider = selected
                }
                updateCloudProviderFields(); updateJurisdictionWarning()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        fun updateUploadMethodFields() {
            val useCloud = uploadMethodGroup.checkedRadioButtonId == uploadMethodCloud.id
            safLayout.visibility = if (!useCloud) View.VISIBLE else View.GONE
            cloudLayout.visibility = if (useCloud) View.VISIBLE else View.GONE
        }
        uploadMethodGroup.setOnCheckedChangeListener { _, _ -> updateUploadMethodFields() }
        updateUploadMethodFields()
        updateCloudProviderFields()

        // === Local Storage ===

        val storageCard = section(getString(R.string.set_local_storage))
        val keepLocalCopy = Ui.checkbox(this, getString(R.string.set_keep_local), Prefs.keepLocalCopy(this)).also { storageCard.addView(it) }
        val archiveLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        archiveLayout.addView(label(getString(R.string.set_archive_max)))
        val archiveMaxEdit = Ui.field(this, getString(R.string.set_archive_max_hint)).also { number(it); it.setText(Prefs.localArchiveMaxMb(this).toString()); archiveLayout.addView(it) }
        storageCard.addView(archiveLayout)
        val queueMb = UploadQueue.queueSizeBytes(this) / (1024.0 * 1024)
        val archiveMb = UploadQueue.archiveSizeBytes(this) / (1024.0 * 1024)
        storageCard.addView(Ui.caption(this, getString(R.string.set_storage_usage, queueMb, archiveMb)).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(10)
        })
        fun updateArchiveVisibility() { archiveLayout.visibility = if (keepLocalCopy.isChecked) View.VISIBLE else View.GONE }
        keepLocalCopy.setOnCheckedChangeListener { _, _ -> updateArchiveVisibility() }
        updateArchiveVisibility()

        // === App Security ===
        val securityCard = section(getString(R.string.set_security))
        val appPinLabel = label(getString(R.string.set_pin_label))
        securityCard.addView(appPinLabel)
        val unlockCodeEdit = Ui.field(this, getString(if (Prefs.isUnlockCodeSet(this)) R.string.set_code_already_set
            else R.string.set_pin_required_hint)).also {
            it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            it.filters = arrayOf(android.text.InputFilter.LengthFilter(16))
            it.isSaveEnabled = false
            securityCard.addView(it)
        }


        val duressFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        securityCard.addView(duressFields)
        duressFields.addView(label(getString(R.string.set_duress_label)))
        val duressCodeEdit = Ui.field(this, getString(if (Prefs.isDuressCodeSet(this)) R.string.set_code_already_set else R.string.set_duress_hint)).also {
            number(it); duressFields.addView(it)
        }
        duressFields.addView(Ui.caption(this, getString(R.string.set_duress_explain)).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(6)
        })

        // === Calculator Disguise ===
        val disguiseCard = section(getString(R.string.set_disguise))
        val disguiseToggle = Ui.checkbox(this, getString(R.string.set_disguise_toggle), Prefs.isCalculatorDisguiseEnabled(this)).also { disguiseCard.addView(it) }
        disguiseCard.addView(Ui.caption(this, getString(R.string.set_disguise_warning)))


        // === Recording Mode ===
        val modeCard = section(getString(R.string.set_mode), advancedBody)
        val modeGroup = RadioGroup(this)
        val modeEvidence = Ui.radio(this, getString(R.string.set_mode_evidence))
        val modeAnonymous = Ui.radio(this, getString(R.string.set_mode_anonymous))
        modeGroup.addView(modeEvidence)
        modeGroup.addView(modeAnonymous)
        when (Prefs.recordingMode(this)) {
            "evidence" -> modeGroup.check(modeEvidence.id)
            "anonymous" -> modeGroup.check(modeAnonymous.id)
        }
        modeCard.addView(modeGroup)
        modeCard.addView(Ui.caption(this, getString(R.string.set_mode_explain)))

        // === Location ===
        val locationCard = section(getString(R.string.set_location), advancedBody)
        val gpsToggle = Ui.checkbox(this, getString(R.string.set_gps_toggle), Prefs.isGpsEnabled(this)).also { locationCard.addView(it) }
        gpsCheckbox = gpsToggle
        gpsToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (modeGroup.checkedRadioButtonId == modeAnonymous.id) {
                    gpsToggle.isChecked = false
                    Toast.makeText(this, getString(R.string.set_gps_anon_blocked), Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                val hasLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!hasLoc) {
                    gpsToggle.isChecked = false
                    requestLocationPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        }
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == modeAnonymous.id) { gpsToggle.isChecked = false; gpsToggle.isEnabled = false }
            else gpsToggle.isEnabled = true
        }
        if (modeGroup.checkedRadioButtonId == modeAnonymous.id) { gpsToggle.isChecked = false; gpsToggle.isEnabled = false }
        locationCard.addView(Ui.caption(this, getString(R.string.set_gps_warning)))

        // === Network Privacy ===
        val netCard = section(getString(R.string.set_network))
        val secureNetToggle = Ui.checkbox(this, getString(R.string.set_secure_net), Prefs.isSecureNetworkRequired(this)).also { netCard.addView(it) }
        netCard.addView(Ui.caption(this, getString(R.string.set_secure_net_explain)))
        val torToggle = Ui.checkbox(this, getString(R.string.set_tor_toggle), Prefs.isTorEnabled(this)).also {
            (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8); netCard.addView(it)
        }
        netCard.addView(Ui.caption(this, getString(R.string.set_tor_explain)))
        val netStatus = Ui.caption(this, "").apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8) }
        netCard.addView(netStatus)
        val checkStatusBtn = Ui.button(this, getString(R.string.set_check_network), Ui.Btn.SECONDARY).also { netCard.addView(it) }
        val torHelpBtn = Ui.button(this, getString(R.string.set_setup_orbot), Ui.Btn.GHOST).also { netCard.addView(it) }
        fun updateNetPrivacyUI() { torHelpBtn.visibility = if (torToggle.isChecked) View.VISIBLE else View.GONE }
        torToggle.setOnCheckedChangeListener { _, _ -> updateNetPrivacyUI() }
        updateNetPrivacyUI()

        checkStatusBtn.setOnClickListener {
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) { TorManager.getStatusText(this@SettingsActivity) }
                netStatus.text = text
            }
        }
        torHelpBtn.setOnClickListener {
            lifecycleScope.launch {
                if (!TorManager.isOrbotInstalled(this@SettingsActivity)) {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(getString(R.string.set_orbot_install_title))
                        .setMessage(getString(R.string.set_orbot_install_msg))
                        .setPositiveButton(getString(R.string.set_orbot_install_action)) { _, _ ->
                            try { TorManager.getOrbotInstallIntent(this@SettingsActivity)?.let { startActivity(it) } }
                            catch (_: Exception) { Toast.makeText(this@SettingsActivity, getString(R.string.set_orbot_cant_store), Toast.LENGTH_SHORT).show() }
                        }
                        .setNegativeButton(getString(R.string.action_cancel), null)
                        .show()
                } else {
                    val reachable = withContext(Dispatchers.IO) { TorManager.isProxyReachable() }
                    if (!reachable) {
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle(getString(R.string.set_orbot_start_title))
                            .setMessage(getString(R.string.set_orbot_start_msg))
                            .setPositiveButton(getString(R.string.set_orbot_open)) { _, _ ->
                                try { TorManager.getOrbotLaunchIntent(this@SettingsActivity)?.let { startActivity(it) } }
                                catch (_: Exception) { Toast.makeText(this@SettingsActivity, getString(R.string.set_orbot_cant_open), Toast.LENGTH_SHORT).show() }
                            }
                            .setNegativeButton(getString(R.string.action_cancel), null)
                            .show()
                    } else {
                        Toast.makeText(this@SettingsActivity, getString(R.string.set_tor_connected), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val batteryCard = section(getString(R.string.battery_setup_title))
        batteryCard.addView(Ui.caption(this, getString(R.string.battery_setup_body)))
        val batteryStatus = Ui.caption(this, getString(if (com.fieldnotes.recorder.util.OemUtil.isBatteryExempt(this))
            R.string.battery_setup_allowed else R.string.battery_setup_restricted))
        batteryCard.addView(batteryStatus)
        refreshBatteryStatus = {
            batteryStatus.setText(if (com.fieldnotes.recorder.util.OemUtil.isBatteryExempt(this))
                R.string.battery_setup_allowed else R.string.battery_setup_restricted)
        }
        batteryCard.addView(Ui.button(this, getString(R.string.battery_setup_open), Ui.Btn.SECONDARY).apply {
            setOnClickListener {
                if (!com.fieldnotes.recorder.util.OemUtil.launchBatteryExemptionSettings(this@SettingsActivity))
                    Toast.makeText(this@SettingsActivity, R.string.battery_setup_unavailable, Toast.LENGTH_LONG).show()
            }
        })
        com.fieldnotes.recorder.util.OemUtil.backgroundPermissionGuidance(this)?.let { guidance ->
            batteryCard.addView(Ui.caption(this, guidance))
            batteryCard.addView(Ui.button(this, getString(R.string.oem_guide_open), Ui.Btn.GHOST).apply {
                setOnClickListener {
                    if (!com.fieldnotes.recorder.util.OemUtil.launchOemBatterySettings(this@SettingsActivity))
                        com.fieldnotes.recorder.util.OemUtil.launchBatteryExemptionSettings(this@SettingsActivity)
                }
            })
        }

        root.addView(advancedCard)

        // === Save ===

        root.addView(Ui.spacer(this, 8))
        val save = Ui.button(this, getString(R.string.set_save), Ui.Btn.PRIMARY).also { root.addView(it) }

        setContentView(scrollView)

        // === Event handlers ===
        save.setOnClickListener {

            if (!video.isChecked && !audio.isChecked) {
                Toast.makeText(this, R.string.capture_select_track, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val exportMode = when (exportGroup.checkedRadioButtonId) {
                exportEncrypted.id -> "encrypted"
                exportPlain.id -> "plaintext"
                else -> "unconfigured"
            }
            if (exportMode == "unconfigured") {
                advancedBody.visibility = View.VISIBLE
                Toast.makeText(this, getString(R.string.upload_block_choice), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (exportMode == "encrypted") {
                try {
                    com.fieldnotes.recorder.crypto.RecipientEncryption.parsePublicKey(recipientKey.text.toString())
                } catch (e: Exception) {
                    advancedBody.visibility = View.VISIBLE
                    Toast.makeText(this, getString(R.string.export_invalid_key), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }
            val requestedFps = nativeRates.getOrNull(fpsSpinner.selectedItemPosition)
            if (requestedFps == null && video.isChecked) {
                Toast.makeText(this, getString(R.string.fail_full_view), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!bitrateChoice.validate()) return@setOnClickListener
            bitrateChoice.save()
            Prefs.setAdaptiveVideoBitrate(this, adaptiveBitrate.isChecked)
            Prefs.setExportPolicy(this, exportMode, if (exportMode == "plaintext") "" else recipientKey.text.toString().trim())
            Prefs.setCameraBackend(this, if (backendGroup.checkedRadioButtonId == cameraX.id) "camerax" else "camera2")
            if (requestedFps != null) {
                val preset = when (requestedFps) {
                    normalRate -> "balanced"; 15 -> "battery"; 10 -> "minimum"; else -> "custom"
                }
                Prefs.setVideoPreset(this, preset)
                Prefs.setVideoFps(this, requestedFps)
                Prefs.setVideoCodec(this, "h264")
                Prefs.setVideoGopSeconds(this, gopEdit.text.toString().toIntOrNull() ?: 2)
                // Frame-rate selection never changes the existing audio configuration.
            }

            Prefs.setVideoEnabled(this, video.isChecked)
            Prefs.setAudioEnabled(this, audio.isChecked)
            Prefs.setWakelockEnabled(this, wakelock.isChecked)
            Prefs.setSegmentDurationSeconds(this, segmentSecs.text.toString().toIntOrNull() ?: 10)


            val selectedMethod = if (uploadMethodGroup.checkedRadioButtonId == uploadMethodSaf.id) "saf" else "cloud"
            Prefs.setUploadMethod(this, selectedMethod)

            val selectedProvider = when (cloudProviderSpinner.selectedItemPosition) {
                0 -> "dropbox"; 1 -> "google_drive"; 2 -> "webdav"; 3 -> "nextcloud"; else -> "dropbox"
            }
            Prefs.setCloudProvider(this, selectedProvider)
            when (selectedProvider) {
                "dropbox", "google_drive" -> {
                    Prefs.setCloudAccessToken(this, accessTokenEdit.text.toString().trim().ifEmpty { null })
                    Prefs.setCloudAppKey(this, appKeyEdit.text.toString().trim().ifEmpty { null })
                    Prefs.setCloudRefreshToken(this, refreshTokenEdit.text.toString().trim().ifEmpty { null })
                }
                "nextcloud" -> {
                    Prefs.setNcBaseUrl(this, ncUrlEdit.text.toString().trim().ifEmpty { null })
                    Prefs.setNcUsername(this, ncUserEdit.text.toString().trim().ifEmpty { null })
                    Prefs.setNcAppPassword(this, ncPassEdit.text.toString().ifEmpty { null })
                    Prefs.setNcFolder(this, ncFolderEdit.text.toString().trim().ifEmpty { null })
                    Prefs.setNcCertPin(this, ncCertPinEdit.text.toString().trim().ifEmpty { null })
                    Prefs.setNcChunkMb(this, ncChunkEdit.text.toString().toIntOrNull() ?: 10)
                }
                else -> {
                    Prefs.setCloudUsername(this, usernameEdit.text.toString().trim().ifEmpty { null })
                    Prefs.setCloudPassword(this, passwordEdit.text.toString().ifEmpty { null })
                    if (selectedProvider == "webdav") {
                        Prefs.setCloudWebdavUrl(this, webdavUrlEdit.text.toString().trim().ifEmpty { null })
                    }
                }
            }
            Prefs.setCloudFolderPath(this, folderPathEdit.text.toString().trim().ifEmpty { "/Backups" })


            Prefs.setKeepLocalCopy(this, keepLocalCopy.isChecked)
            if (keepLocalCopy.isChecked) {
                Prefs.setLocalArchiveMaxMb(this, archiveMaxEdit.text.toString().toIntOrNull() ?: 500)
            }

            Prefs.setGpsEnabled(this, gpsToggle.isChecked)

            val selectedMode = if (modeGroup.checkedRadioButtonId == modeAnonymous.id) "anonymous" else "evidence"
            Prefs.setRecordingMode(this, selectedMode)

            Prefs.setSecureNetworkRequired(this, secureNetToggle.isChecked)
            Prefs.setTorEnabled(this, torToggle.isChecked)

            val disguiseEnabled = disguiseToggle.isChecked
            Prefs.setCalculatorDisguiseEnabled(this, disguiseEnabled)
            val calcAlias = android.content.ComponentName(this, "com.fieldnotes.recorder.ui.CalculatorLauncher")
            val newState = if (disguiseEnabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                           else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            packageManager.setComponentEnabledSetting(calcAlias, newState, PackageManager.DONT_KILL_APP)

            val code = unlockCodeEdit.text.toString().trim()
            val weakCodes = setOf(
                "0000", "1111", "2222", "3333", "4444", "5555", "6666", "7777",
                "8888", "9999", "1234", "4321", "0123", "3210", "1152"
            )
            if (code.length >= 4 && code !in weakCodes && code.toSet().size > 1) {
                Prefs.setUnlockCode(this, code)
            } else if (code.isNotEmpty()) {
                Toast.makeText(this, getString(R.string.set_err_unlock_weak), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val duress = duressCodeEdit.text.toString().trim()
            if (duress.isNotEmpty()) {
                when {
                    duress.length < 4 -> {
                        Toast.makeText(this, getString(R.string.set_err_duress_short), Toast.LENGTH_LONG).show(); return@setOnClickListener
                    }
                    // Compare against the STORED unlock code, not just a code typed
                    // in this same save. Leaving the PIN field blank (the normal
                    // case once a PIN exists) previously skipped this check
                    // entirely, so a duress code equal to the existing PIN was
                    // accepted — and since both lock screens test duress FIRST,
                    // the user's own PIN would then silently wipe everything.
                    // A new code typed above has already been persisted, so this
                    // covers both the "changing the PIN now" and "PIN set earlier"
                    // cases with one condition.
                    Prefs.isUnlockCodeSet(this) && Prefs.verifyUnlockCode(this, duress) -> {
                        Toast.makeText(this, getString(R.string.set_err_duress_same), Toast.LENGTH_LONG).show(); return@setOnClickListener
                    }
                    duress in weakCodes || duress.toSet().size == 1 -> {
                        Toast.makeText(this, getString(R.string.set_err_duress_weak), Toast.LENGTH_LONG).show(); return@setOnClickListener
                    }
                    else -> Prefs.setDuressCode(this, duress)
                }
            } else {
                Prefs.setDuressCode(this, null)
            }

            com.fieldnotes.recorder.upload.UploadScheduler.ensurePeriodicDrain(this)
            com.fieldnotes.recorder.upload.UploadScheduler.retryNow(this)
            finish()
        }

        chooseCloud.setOnClickListener {
            pickFolder.launch(Prefs.cloudFolderUri(this)?.let { Uri.parse(it) })
        }

        testButton.setOnClickListener {
            testResult.text = getString(R.string.nc_testing)
            lifecycleScope.launch {
                try {
                    val sp = when (cloudProviderSpinner.selectedItemPosition) {
                        0 -> "dropbox"; 1 -> "google_drive"; 2 -> "webdav"; 3 -> "nextcloud"; else -> "dropbox"
                    }
                    Prefs.setCloudProvider(this@SettingsActivity, sp)
                    when (sp) {
                        "dropbox", "google_drive" -> {
                            Prefs.setCloudAccessToken(this@SettingsActivity, accessTokenEdit.text.toString().trim().ifEmpty { null })
                            Prefs.setCloudAppKey(this@SettingsActivity, appKeyEdit.text.toString().trim().ifEmpty { null })
                            Prefs.setCloudRefreshToken(this@SettingsActivity, refreshTokenEdit.text.toString().trim().ifEmpty { null })
                        }
                        "nextcloud" -> {
                            Prefs.setNcBaseUrl(this@SettingsActivity, ncUrlEdit.text.toString().trim().ifEmpty { null })
                            Prefs.setNcUsername(this@SettingsActivity, ncUserEdit.text.toString().trim().ifEmpty { null })
                            Prefs.setNcAppPassword(this@SettingsActivity, ncPassEdit.text.toString().ifEmpty { null })
                            Prefs.setNcFolder(this@SettingsActivity, ncFolderEdit.text.toString().trim().ifEmpty { null })
                            Prefs.setNcCertPin(this@SettingsActivity, ncCertPinEdit.text.toString().trim().ifEmpty { null })
                            Prefs.setNcChunkMb(this@SettingsActivity, ncChunkEdit.text.toString().toIntOrNull() ?: 10)
                        }
                        else -> {
                            Prefs.setCloudUsername(this@SettingsActivity, usernameEdit.text.toString().trim().ifEmpty { null })
                            Prefs.setCloudPassword(this@SettingsActivity, passwordEdit.text.toString().ifEmpty { null })
                            if (sp == "webdav") {
                                Prefs.setCloudWebdavUrl(this@SettingsActivity, webdavUrlEdit.text.toString().trim().ifEmpty { null })
                            }
                        }
                    }
                    val manager = CloudUploadManager(this@SettingsActivity)
                    val success = manager.testConnection()
                    testResult.text = getString(if (success) R.string.set_test_ok else R.string.set_test_fail)
                } catch (e: Exception) {
                    testResult.text = getString(R.string.set_test_fail)
                }
            }
        }
    }

    private fun showCloudSetupHelp(provider: String) {
        val title = when (provider) {
            "dropbox" -> getString(R.string.help_dropbox_title)
            "google_drive" -> getString(R.string.help_gdrive_title)
            "webdav" -> getString(R.string.help_webdav_title)
            "nextcloud" -> getString(R.string.help_nextcloud_title)
            else -> getString(R.string.help_cloud_title)
        }
        val body = when (provider) {
            "dropbox" -> dropboxHelpText()
            "google_drive" -> googleDriveHelpText()
            "webdav" -> webdavHelpText()
            "nextcloud" -> nextcloudHelpText()
            else -> ""
        }

        val scrollView = ScrollView(this)
        val tv = TextView(this).apply {
            text = body
            textSize = 13f
            setPadding(40, 30, 40, 30)
            setTextIsSelectable(true)
        }
        scrollView.addView(tv)

        val (linkLabel, linkUrl) = when (provider) {
            "dropbox" -> getString(R.string.help_dropbox_console) to "https://www.dropbox.com/developers/apps"
            "google_drive" -> getString(R.string.help_oauth_playground) to "https://developers.google.com/oauthplayground"
            "webdav" -> getString(R.string.help_about_nextcloud) to "https://nextcloud.com/"
            "nextcloud" -> getString(R.string.help_about_nextcloud) to "https://nextcloud.com/"
            else -> getString(R.string.action_open) to "https://www.dropbox.com/developers/apps"
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton(getString(R.string.action_close), null)
            .setNeutralButton(linkLabel) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl)))
                } catch (_: Exception) {
                    Toast.makeText(this, getString(R.string.no_browser), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun dropboxHelpText(): String = getString(R.string.help_dropbox_body)

    private fun googleDriveHelpText(): String = getString(R.string.help_gdrive_body)

    private fun webdavHelpText(): String = getString(R.string.help_webdav_body)

    private fun nextcloudHelpText(): String = getString(R.string.help_nextcloud_body)

    // Re-lock gate (audit H9): Settings exposes cloud credentials and server
    // URLs, so it must re-prompt for the PIN after backgrounding just like
    // MainActivity. See SessionLock.
    override fun onStart() {
        super.onStart()
        trackForegroundStart()
    }

    override fun onResume() {
        super.onResume()
        if (enforceSessionLock()) return
        refreshElectionConnection?.invoke()
        refreshCameraSummary?.invoke()
        refreshBatteryStatus?.invoke()
    }

    override fun onStop() {
        super.onStop()
        trackForegroundStop()
    }
}
