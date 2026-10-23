package com.fieldnotes.recorder.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import com.fieldnotes.recorder.R
import com.fieldnotes.recorder.util.Prefs

/** One editor for setup and settings. Values are saved only after validation. */
class BitrateChoice(ctx: Context) : LinearLayout(ctx) {
    private val rates = listOf(500, 1000, 2000, 4000, 8000)
    private val legacy = Prefs.videoBitrateMode(ctx) == "bpp"
    private val labels = mutableListOf(ctx.getString(R.string.bitrate_auto)).apply {
        addAll(rates.map { ctx.getString(R.string.bitrate_rate, it) })
        add(ctx.getString(R.string.bitrate_custom))
        if (legacy) add(ctx.getString(R.string.bitrate_legacy))
    }
    private val customIndex = rates.size + 1
    private val picker = Spinner(ctx)
    private val input = Ui.field(ctx, ctx.getString(R.string.bitrate_custom_hint))
    private val estimate = Ui.caption(ctx, "")

    init {
        orientation = VERTICAL
        addView(Ui.title(ctx, ctx.getString(R.string.bitrate_title)))
        addView(Ui.caption(ctx, ctx.getString(R.string.bitrate_help)))
        addView(picker)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(Prefs.videoTargetKbps(ctx).toString())
        input.contentDescription = ctx.getString(R.string.bitrate_custom_hint)
        addView(input)
        addView(estimate)
        picker.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        picker.setSelection(when (Prefs.videoBitrateMode(ctx)) {
            "bpp" -> labels.lastIndex
            "target" -> rates.indexOf(Prefs.videoTargetKbps(ctx)).let { if (it < 0) customIndex else it + 1 }
            else -> 0
        })
        fun refresh() {
            input.visibility = if (picker.selectedItemPosition == customIndex) View.VISIBLE else View.GONE
            val rate = selectedRate()
            estimate.text = if (rate == null) ctx.getString(R.string.bitrate_auto_help) else
                ctx.getString(R.string.bitrate_estimate, rate * 60.0 / 8000.0)
        }
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        refresh()
    }

    private fun selectedRate(): Int? = if (picker.selectedItemPosition == customIndex)
        input.text.toString().toIntOrNull() else rates.getOrNull(picker.selectedItemPosition - 1)

    fun validate(): Boolean {
        if (picker.selectedItemPosition == customIndex && selectedRate() !in 200..50000) {
            input.error = context.getString(R.string.bitrate_invalid)
            input.requestFocus()
            return false
        }
        return true
    }

    fun draft() = android.os.Bundle().apply {
        putInt("selection", picker.selectedItemPosition)
        putString("input", input.text.toString())
    }

    fun restoreDraft(draft: android.os.Bundle?) {
        if (draft == null) return
        picker.setSelection(draft.getInt("selection").coerceIn(0, labels.lastIndex))
        input.setText(draft.getString("input", ""))
    }

    fun save() {
        check(validate())
        when (picker.selectedItemPosition) {
            0 -> Prefs.setVideoBitrateMode(context, "auto")
            customIndex + 1 -> Prefs.setVideoBitrateMode(context, "bpp")
            else -> {
                Prefs.setVideoTargetKbps(context, checkNotNull(selectedRate()))
                Prefs.setVideoBitrateMode(context, "target")
            }
        }
    }
}
