package com.fieldnotes.recorder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fieldnotes.recorder.R
import com.fieldnotes.recorder.capture.CalibrationStore
import com.fieldnotes.recorder.capture.CameraCalibration
import com.fieldnotes.recorder.capture.CaptureStatus
import com.fieldnotes.recorder.util.CameraInfo
import com.fieldnotes.recorder.util.CameraUtil
import com.fieldnotes.recorder.util.Prefs
import kotlinx.coroutines.*
import kotlin.math.abs

class CalibrationActivity : AppCompatActivity() {
    private data class Row(val status: TextView, val test: Button, val view: Button, val choose: Button,
        var preview: CalibrationStore.Preview? = null, var failed: Boolean = false)
    private val rows = linkedMapOf<Pair<Int, Int>, Row>()
    private lateinit var camera: CameraInfo
    private lateinit var testAll: Button
    private lateinit var automatic: Button
    private lateinit var clear: Button
    private lateinit var selected: TextView
    private var normalSize: Pair<Int, Int>? = null
    private var busy = false
    private var testJob: Job? = null
    private var previewDialog: AlertDialog? = null
    private val pal by lazy { Ui.palette(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        try { setTaskDescription(android.app.ActivityManager.TaskDescription(com.fieldnotes.recorder.util.Disguise.taskLabel(this))) } catch (_: Exception) {}
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        fun message(id: Int) { root.addView(Ui.caption(this, getString(id))) }
        if (CaptureStatus.running) { message(R.string.settings_recording_active); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            message(R.string.calib_no_permission); return
        }
        val info = CameraUtil.getCameraInfo(this)
        if (info == null) { message(R.string.calib_no_info); return }
        camera = info
        com.fieldnotes.recorder.util.CameraUtil.applyVideoPreset(this)
        root.addView(TextView(this).apply { text = getString(R.string.calib_title); textSize = 22f })
        root.addView(Ui.caption(this, getString(R.string.calib_sensor, info.sensorSize.first, info.sensorSize.second,
            String.format("%.2f", info.sensorAspectRatio))))
        root.addView(Ui.caption(this, getString(R.string.calib_intro, 3)))
        message(R.string.calib_preview_privacy)
        val sources = runCatching { CameraUtil.nativeFullViewSizes(this).filter { Prefs.videoFps(this) in CameraUtil.nativeFpsOptions(this, it) } }.getOrDefault(emptyList())
        if (sources.isEmpty()) { message(R.string.fail_full_view); return }
        normalSize = CameraUtil.normalVideoResolution(this)
        message(R.string.calib_source_help)
        selected = Ui.caption(this, "").also { root.addView(it) }
        automatic = Ui.button(this, getString(R.string.calib_use_auto), Ui.Btn.SECONDARY).apply {
            setOnClickListener {
                if (CaptureStatus.running || busy) return@setOnClickListener
                Prefs.clearVerifiedNativeResolution(this@CalibrationActivity, camera.cameraId)
                Prefs.setFullCameraView(this@CalibrationActivity, true)
                updateRows()
            }
        }.also { root.addView(it) }
        testAll = Ui.button(this, getString(R.string.calib_test_all), Ui.Btn.PRIMARY).apply {
            setOnClickListener { runTests(rows.keys.toList()) }
        }.also { root.addView(it) }
        for (size in sources) {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 24, 0, 16) }
            val matches = abs(size.first.toDouble() / size.second - info.sensorAspectRatio) < 0.05
            card.addView(TextView(this).apply { text = "${size.first} × ${size.second}${if (matches) " *" else ""}"; textSize = 18f })
            val status = Ui.caption(this, getString(R.string.calib_not_tested)).also { card.addView(it) }
            val test = Ui.button(this, getString(R.string.calib_test), Ui.Btn.SECONDARY).apply {
                setOnClickListener { runTests(listOf(size)) }
            }.also { card.addView(it) }
            val view = Ui.button(this, getString(R.string.calib_view_frame), Ui.Btn.SECONDARY).apply {
                setOnClickListener { showPreview(size) }
            }.also { card.addView(it) }
            val choose = Ui.button(this, getString(R.string.calib_use_default), Ui.Btn.SECONDARY).apply {
                setOnClickListener { confirmNativeView(size) }
            }.also { card.addView(it) }
            rows[size] = Row(status, test, view, choose)
            root.addView(card)
        }
        message(R.string.calib_footnote)
        clear = Ui.button(this, getString(R.string.calib_clear_previews), Ui.Btn.SECONDARY).apply {
            setOnClickListener {
                if (busy) return@setOnClickListener
                lifecycleScope.launch {
                    busy = true; updateRows()
                    withContext(Dispatchers.IO) { CalibrationStore.clear(this@CalibrationActivity) }
                    rows.values.forEach { it.preview = null; it.failed = false }
                    busy = false; updateRows()
                }
            }
        }.also { root.addView(it) }
        busy = true; updateRows()
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                rows.keys.associateWith { CalibrationStore.load(this@CalibrationActivity, camera.cameraId, it) }
            }
            saved.forEach { (size, preview) -> rows[size]?.preview = preview }
            busy = false; updateRows()
        }
    }

    private fun updateRows() {
        if (!::camera.isInitialized) return
        if (!::selected.isInitialized) return
        val chosen = Prefs.verifiedNativeResolution(this, camera.cameraId, CalibrationStore.signature(this)) ?: normalSize
        selected.text = if (chosen == null) getString(R.string.calib_default_auto)
            else getString(R.string.calib_default_size, chosen.first, chosen.second)
        val signature = CalibrationStore.signature(this)
        for ((size, row) in rows) {
            val preview = row.preview
            val valid = preview != null && preview.signature == signature && !row.failed
            row.test.isEnabled = !busy
            row.view.isEnabled = !busy && preview != null
            row.choose.isEnabled = !busy && valid && size != chosen && rows[normalSize]?.preview?.signature == signature && rows[normalSize]?.failed == false
            row.choose.text = getString(if (chosen == size) R.string.calib_is_default else R.string.calib_use_default)
            if (!busy) {
                row.status.text = when {
                    row.failed -> getString(R.string.calib_failed)
                    preview == null -> getString(R.string.calib_not_tested)
                    !valid -> getString(R.string.calib_preview_stale)
                    else -> getString(R.string.calib_pass_preview, preview.frames, preview.fps)
                }
                row.status.setTextColor(if (valid) pal.success else if (row.failed) pal.danger else pal.textSecondary)
            }
        }
        testAll.isEnabled = !busy
        automatic.isEnabled = !busy
        if (::clear.isInitialized) clear.isEnabled = !busy && rows.values.any { it.preview != null }
    }

    private fun runTests(sizes: List<Pair<Int, Int>>) {
        if (busy || CaptureStatus.running) return
        testJob = lifecycleScope.launch {
            busy = true; updateRows()
            try {
                for (size in sizes) {
                    val row = rows.getValue(size)
                    row.status.text = getString(R.string.calib_testing)
                    row.status.setTextColor(pal.textSecondary)
                    try {
                        row.preview = CameraCalibration.test(this@CalibrationActivity, camera, size)
                        row.failed = false
                        row.status.text = getString(R.string.calib_pass_preview, row.preview!!.frames, row.preview!!.fps)
                        row.status.setTextColor(pal.success)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        row.failed = true
                        row.status.text = getString(R.string.calib_failed)
                        row.status.setTextColor(pal.danger)
                    }
                    delay(300)
                }
            } finally { busy = false; updateRows() }
        }
    }

    private fun confirmNativeView(size: Pair<Int, Int>) {
        if (busy || CaptureStatus.running) return
        val signature = CalibrationStore.signature(this)
        val reference = rows[normalSize]?.takeIf { !it.failed }?.preview
        val candidate = rows[size]?.takeIf { !it.failed }?.preview
        if (reference?.signature != signature || candidate?.signature != signature) {
            Toast.makeText(this, R.string.native_compare_required, Toast.LENGTH_LONG).show(); return
        }
        if (size == normalSize) { choose(size); return }
        val images = mutableListOf<android.graphics.Bitmap>()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,16,16,16) }
        try {
            for ((label, preview) in listOf(R.string.native_reference to reference, R.string.native_candidate to candidate)) {
                layout.addView(Ui.caption(this, getString(label)))
                val bitmap = preview.bitmap().also { images.add(it) }
                layout.addView(ImageView(this).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.displayMetrics.heightPixels / 4))
            }
            layout.addView(Ui.caption(this, getString(R.string.native_compare_help)))
            previewDialog?.dismiss()
            previewDialog = AlertDialog.Builder(this).setTitle(R.string.native_compare_title)
                .setView(ScrollView(this).apply { addView(layout) })
                .setPositiveButton(R.string.native_confirm_same_view) { _, _ -> choose(size) }
                .setNegativeButton(R.string.action_cancel, null).create().apply {
                    window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    setOnDismissListener { layout.removeAllViews(); images.forEach { it.recycle() }; previewDialog = null }
                    show()
                }
        } catch (_: Exception) { images.forEach { it.recycle() } }
    }

    private fun choose(size: Pair<Int, Int>) {
        val row = rows.getValue(size)
        if (busy || CaptureStatus.running || row.failed || row.preview?.signature != CalibrationStore.signature(this)) return
        try {
            Prefs.setVerifiedNativeResolution(this, camera.cameraId, size, CalibrationStore.signature(this))
            updateRows()
            Toast.makeText(this, getString(R.string.calib_default_saved, size.first, size.second), Toast.LENGTH_LONG).show()
        } catch (_: Exception) { Toast.makeText(this, getString(R.string.calib_save_failed), Toast.LENGTH_LONG).show() }
    }

    private fun showPreview(size: Pair<Int, Int>) {
        val row = rows.getValue(size)
        val preview = row.preview ?: return
        val bitmap = runCatching { preview.bitmap() }.getOrNull() ?: return
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            minimumHeight = 240
            contentDescription = getString(R.string.calib_frame_description, size.first, size.second)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16)
            addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.displayMetrics.heightPixels / 2))
            addView(Ui.caption(this@CalibrationActivity, getString(R.string.calib_frame_help)))
            if (row.failed || preview.signature != CalibrationStore.signature(this@CalibrationActivity))
                addView(Ui.caption(this@CalibrationActivity, getString(R.string.calib_preview_stale)))
        }
        val builder = AlertDialog.Builder(this).setTitle("${size.first} × ${size.second}").setView(layout)
            .setNegativeButton(R.string.action_close, null)
        if (!row.failed && preview.signature == CalibrationStore.signature(this)) {
            builder.setPositiveButton(R.string.calib_use_default) { _, _ -> confirmNativeView(size) }
        }
        previewDialog = builder.create().apply {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setOnDismissListener { image.setImageDrawable(null); bitmap.recycle(); previewDialog = null }
            show()
        }
    }

    override fun onStart() { super.onStart(); trackForegroundStart() }
    override fun onResume() { super.onResume(); enforceSessionLock() }
    override fun onStop() {
        testJob?.cancel()
        previewDialog?.dismiss()
        super.onStop(); trackForegroundStop()
    }
}
