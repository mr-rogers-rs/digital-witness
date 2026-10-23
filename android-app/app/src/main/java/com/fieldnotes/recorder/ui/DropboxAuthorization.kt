package com.fieldnotes.recorder.ui

import com.fieldnotes.recorder.R
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.fieldnotes.recorder.network.PinnedHttpClient
import com.fieldnotes.recorder.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Browser + copy-code PKCE flow. No app secret or embedded browser. */
object DropboxAuthorization {
    fun show(activity: ComponentActivity, appKey: String, completed: (String, String) -> Unit) {
        if (appKey.isBlank()) {
            Toast.makeText(activity, activity.getString(R.string.dropbox_key_required), Toast.LENGTH_LONG).show()
            return
        }
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32,16,32,16) }
        col.addView(Ui.caption(activity, activity.getString(R.string.dropbox_auth_intro)))
        val code = Ui.field(activity, activity.getString(R.string.dropbox_auth_code))
        col.addView(code)
        val start = Ui.button(activity, activity.getString(R.string.dropbox_auth_open), Ui.Btn.SECONDARY)
        col.addView(start)
        val finish = Ui.button(activity, activity.getString(R.string.dropbox_auth_connect), Ui.Btn.PRIMARY)
        col.addView(finish)
        val dialog = AlertDialog.Builder(activity).setTitle(activity.getString(R.string.dropbox_auth_title)).setView(col)
            .setNegativeButton(activity.getString(R.string.action_close), null).create()
        start.setOnClickListener {
            val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            Prefs.setOauthPending(activity, JSONObject().put("app", appKey).put("verifier", verifier)
                .put("created", SystemClock.elapsedRealtime()).toString())
            val uri = Uri.parse("https://www.dropbox.com/oauth2/authorize").buildUpon()
                .appendQueryParameter("client_id", appKey).appendQueryParameter("response_type", "code")
                .appendQueryParameter("token_access_type", "offline")
                .appendQueryParameter("code_challenge_method", "S256").appendQueryParameter("code_challenge", challenge)
                .build()
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
        finish.setOnClickListener {
            finish.isEnabled = false
            activity.lifecycleScope.launch {
                try {
                    val pending = JSONObject(Prefs.oauthPending(activity) ?: error("Start authorization first"))
                    require(pending.getString("app") == appKey)
                    val age = SystemClock.elapsedRealtime() - pending.getLong("created")
                    require(age in 0..600_000) { "Authorization expired; start again" }
                    val entered = code.text.toString().trim()
                    require(entered.isNotEmpty())
                    val tokens = withContext(Dispatchers.IO) {
                        val body = FormBody.Builder().add("grant_type", "authorization_code")
                            .add("client_id", appKey).add("code_verifier", pending.getString("verifier"))
                            .add("code", entered).build()
                        val client = PinnedHttpClient.pinnedBuilder(activity).callTimeout(30, TimeUnit.SECONDS).build()
                        client.newCall(Request.Builder().url("https://api.dropboxapi.com/oauth2/token").post(body).build())
                            .execute().use { response ->
                                require(response.isSuccessful) { "Dropbox rejected authorization" }
                                val raw = response.body!!.source().apply { request(65537) }.buffer.clone().readByteArray()
                                require(raw.size <= 65536)
                                JSONObject(raw.toString(Charsets.UTF_8))
                            }
                    }
                    completed(tokens.getString("access_token"), tokens.getString("refresh_token"))
                    Prefs.setOauthPending(activity, null)
                    dialog.dismiss()
                    Toast.makeText(activity, activity.getString(R.string.dropbox_auth_success), Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Toast.makeText(activity, activity.getString(R.string.dropbox_auth_failed), Toast.LENGTH_LONG).show()
                } finally { finish.isEnabled = true }
            }
        }
        dialog.show()
    }
}
