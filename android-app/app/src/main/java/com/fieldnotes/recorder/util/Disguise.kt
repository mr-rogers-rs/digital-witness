package com.fieldnotes.recorder.util

import android.content.Context
import com.fieldnotes.recorder.R

/**
 * Centralizes the app's user-visible "presentation strings" (Recent Apps task
 * label, foreground/alert notification text and channel names) so every
 * surface is consistent with the chosen presentation stance.
 *
 * Decision (audit A8/B3 — "honest with lock"): the app's DEFAULT stance is an
 * honest field recorder protected by a PIN lock, NOT a disguised app. The
 * calculator disguise is an OPT-IN feature (Settings → calculator disguise).
 *
 *  - Disguise OFF (default): everything reads honestly — the launcher is the
 *    PIN lock screen labelled "Field Recorder", Recent Apps shows
 *    "Field Recorder", and the notification says it is recording.
 *  - Disguise ON (opt-in): the covert cover-story is restored across ALL
 *    surfaces (launcher alias → "Calculator", Recent Apps → "Calculator",
 *    notifications → generic "System / Background sync"), so the disguise is
 *    consistent.
 *
 * The previous code hardcoded the covert cover-story everywhere, which left the
 * default install in a contradictory half-state — an honest "Field Recorder"
 * launcher whose Recent Apps card and notifications still said "Calculator" /
 * "System". A mismatch like that is itself suspicious, so all surfaces now read
 * from here.
 *
 * Note: notification lock-screen visibility stays SECRET in BOTH modes — hiding
 * the notification body from a shoulder-surfer is a privacy measure compatible
 * with honest presentation (the app still names itself honestly in the expanded
 * shade), not deception.
 */
object Disguise {

    private fun disguised(ctx: Context): Boolean = Prefs.isCalculatorDisguiseEnabled(ctx)

    /**
     * Picks the covert or honest variant of a paired string. Both variants are
     * resources, so a locale translates the cover story too — a phone whose
     * apps are otherwise Serbian showing one English "Calculator" is its own
     * small tell.
     */
    private fun pick(ctx: Context, covert: Int, honest: Int): String =
        ctx.getString(if (disguised(ctx)) covert else honest)

    /** Recent Apps / task-switcher label. */
    fun taskLabel(ctx: Context): String =
        pick(ctx, R.string.disguise_app_name, R.string.app_name)

    /** Foreground-service (recording) notification title. */
    fun serviceNotificationTitle(ctx: Context): String =
        pick(ctx, R.string.notif_title_covert, R.string.notif_title_honest)

    /** Foreground-service (recording) notification body. */
    fun serviceNotificationText(ctx: Context): String =
        pick(ctx, R.string.notif_text_covert, R.string.notif_text_honest)

    /** Failure/attention alert notification title. */
    fun alertTitle(ctx: Context): String =
        pick(ctx, R.string.notif_alert_title_covert, R.string.notif_alert_title_honest)

    /** Failure/attention alert notification body. */
    fun alertText(ctx: Context): String = ctx.getString(R.string.notif_alert_text)

    /** Notification channel display name. */
    fun channelName(ctx: Context): String =
        pick(ctx, R.string.notif_channel_covert, R.string.notif_channel_honest)

    /** Notification channel description. */
    fun channelDescription(ctx: Context): String =
        pick(ctx, R.string.notif_channel_desc_covert, R.string.notif_channel_desc_honest)
}
