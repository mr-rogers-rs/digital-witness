package com.fieldnotes.recorder.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import com.fieldnotes.recorder.R
import com.fieldnotes.recorder.crypto.RecipientEncryption
import com.fieldnotes.recorder.upload.UploadScheduler
import com.fieldnotes.recorder.util.Prefs

/** An explicit confidentiality choice, usable before recording and during setup. */
fun uploadChoice(context: Context, onSaved: () -> Unit): LinearLayout = Ui.card(context).apply {
    addView(Ui.body(context, context.getString(R.string.export_explanation)))
    val group = RadioGroup(context)
    val plain = Ui.radio(context, context.getString(R.string.export_plaintext))
    val encrypted = Ui.radio(context, context.getString(R.string.export_encrypted))
    group.addView(plain); group.addView(encrypted); addView(group)
    val key = Ui.field(context, context.getString(R.string.export_key_hint)).apply {
        setSingleLine(false); minLines = 3
        setText(Prefs.recipientPublicKey(context))
    }
    addView(key)
    when (Prefs.exportMode(context)) {
        "plaintext" -> group.check(plain.id)
        "encrypted" -> group.check(encrypted.id)
    }
    key.visibility = if (group.checkedRadioButtonId == encrypted.id) View.VISIBLE else View.GONE
    group.setOnCheckedChangeListener { _, id -> key.visibility = if (id == encrypted.id) View.VISIBLE else View.GONE }
    val error = Ui.caption(context, "").apply { setTextColor(Ui.palette(context).danger) }
    addView(error)
    addView(Ui.button(context, context.getString(R.string.upload_save_choice)).apply {
        setOnClickListener {
            val mode = when (group.checkedRadioButtonId) {
                plain.id -> "plaintext"
                encrypted.id -> "encrypted"
                else -> { error.setText(R.string.upload_block_choice); return@setOnClickListener }
            }
            if (mode == "encrypted") try { RecipientEncryption.parsePublicKey(key.text.toString()) }
                catch (_: Exception) { error.setText(R.string.export_invalid_key); return@setOnClickListener }
            Prefs.setExportPolicy(context, mode, if (mode == "encrypted") key.text.toString().trim() else "")
            onSaved()
        }
    })
}

fun showUploadChoice(context: Context, onSaved: () -> Unit) {
    val dialog = AlertDialog.Builder(context).setTitle(R.string.export_title)
        .setNegativeButton(R.string.action_cancel, null).create()
    val scroll = ScrollView(context)
    scroll.addView(uploadChoice(context) {
        UploadScheduler.retryNow(context)
        dialog.dismiss()
        onSaved()
    })
    dialog.setView(scroll)
    dialog.show()
}
