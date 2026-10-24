package com.fieldnotes.recorder.ui

import android.content.Context
import android.content.res.Configuration
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import kotlin.math.ceil

/** Full-width, uncropped preview. Recording controls live outside its scroll container. */
class CameraPreviewFrame(context: Context) : FrameLayout(context) {
    private var aspectRatio = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        16.0 / 9.0 else 9.0 / 16.0

    fun setAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val ratio = width.toDouble() / height
        if (kotlin.math.abs(aspectRatio - ratio) < 0.0001) return
        aspectRatio = ratio
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = ceil(width / aspectRatio).toInt().coerceAtLeast(1)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}
