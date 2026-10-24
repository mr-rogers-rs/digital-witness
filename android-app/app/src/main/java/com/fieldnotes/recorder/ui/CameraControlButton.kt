package com.fieldnotes.recorder.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.widget.AppCompatImageButton

/** Outlined, high-contrast camera toggle with a 48dp touch target and spoken state. */
class CameraControlButton(context: Context) : AppCompatImageButton(context) {
    private var iconResource = 0

    init {
        minimumWidth = context.dp(48)
        minimumHeight = context.dp(48)
        setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        scaleType = ScaleType.CENTER_INSIDE
        val accent = 0xFFFFD166.toInt()
        fun circle(stroke: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC101418.toInt())
            setStroke(context.dp(1).coerceAtLeast(1), stroke)
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), circle(accent))
            addState(intArrayOf(), circle(Color.WHITE))
        }
        val mask = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), states, mask)
        imageTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(0xFF999999.toInt(), accent, Color.WHITE)
        )
    }

    fun show(icon: Int, label: String, active: Boolean, available: Boolean) {
        if (iconResource != icon) { setImageResource(icon); iconResource = icon }
        isSelected = active
        isEnabled = available
        if (contentDescription != label) {
            contentDescription = label
            tooltipText = label
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.ToggleButton"
        info.isCheckable = true
        info.isChecked = isSelected
    }
}
