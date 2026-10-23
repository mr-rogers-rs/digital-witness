package com.fieldnotes.recorder.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.fieldnotes.recorder.R

/** Native language names remain recognizable regardless of the current locale. */
fun languagePicker(context: Context): LinearLayout = Ui.card(context).apply {
    addView(Ui.title(context, context.getString(R.string.language_title)))
    val group = RadioGroup(context)
    val english = Ui.radio(context, context.getString(R.string.language_english))
    val serbian = Ui.radio(context, context.getString(R.string.language_serbian))
    group.addView(english)
    group.addView(serbian)
    val locale = context.resources.configuration.locales[0]
    group.check(if (locale.language == "sr" && locale.script == "Latn") serbian.id else english.id)
    // Clicks also persist an explicit choice when that language already
    // matches the system default. Rendering/restoring radio state never writes.
    english.setOnClickListener {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
    }
    serbian.setOnClickListener {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("sr-Latn"))
    }
    addView(group)
}
