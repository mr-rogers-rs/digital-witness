package com.fieldnotes.recorder.util

import android.content.ComponentName
import android.content.Context
import com.fieldnotes.recorder.R
import android.content.Intent
import android.os.Build

/**
 * Helpers for detecting aggressive-background-kill OEM skins and launching
 * their proprietary "autostart" / "battery optimization" settings pages.
 */
object OemUtil {

    fun isBatteryExempt(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)

    /** Invoked only by a setup/settings button, never while starting capture. */
    fun launchBatteryExemptionSettings(ctx: Context): Boolean {
        val actions = if (isBatteryExempt(ctx)) listOf(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            else listOf(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        for (action in actions) {
            try {
                ctx.startActivity(Intent(action, android.net.Uri.parse("package:${ctx.packageName}")))
                return true
            } catch (_: Exception) { }
        }
        return false
    }

    enum class Oem { XIAOMI, SAMSUNG, HUAWEI, OPPO, OTHER }

    fun detectOem(): Oem {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return when {
            m.contains("xiaomi") || b.contains("xiaomi") ||
            b.contains("redmi") || b.contains("poco") ||
            b.contains("blackshark") || m.contains("redmi") -> Oem.XIAOMI

            m.contains("samsung") || b.contains("samsung") -> Oem.SAMSUNG

            m.contains("huawei") || b.contains("huawei") ||
            m.contains("honor") || b.contains("honor") -> Oem.HUAWEI

            m.contains("oppo") || b.contains("oppo") ||
            m.contains("realme") || b.contains("realme") ||
            m.contains("oneplus") || b.contains("oneplus") -> Oem.OPPO

            else -> Oem.OTHER
        }
    }

    fun isXiaomiDevice(): Boolean = detectOem() == Oem.XIAOMI

    /**
     * Returns a human-readable instruction string for the detected OEM,
     * or null if no special action is needed.
     */
    fun backgroundPermissionGuidance(ctx: Context): String? {
        val app = ctx.getString(R.string.app_name)
        val res = when (detectOem()) {
            Oem.XIAOMI -> R.string.oem_xiaomi
            Oem.SAMSUNG -> R.string.oem_samsung
            Oem.HUAWEI -> R.string.oem_huawei
            Oem.OPPO -> R.string.oem_oppo
            Oem.OTHER -> return null
        }
        return ctx.getString(res, app)
    }

    /**
     * Try to launch the OEM-specific autostart / battery settings page.
     * Returns true if an intent was successfully launched.
     */
    fun launchOemBatterySettings(ctx: Context): Boolean {
        val intents = when (detectOem()) {
            Oem.XIAOMI -> listOf(
                Intent().setComponent(ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )),
                Intent().setComponent(ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings"
                ))
            )
            Oem.SAMSUNG -> listOf(
                Intent().setComponent(ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                ))
            )
            Oem.HUAWEI -> listOf(
                Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )),
                Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                ))
            )
            Oem.OPPO -> listOf(
                Intent().setComponent(ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                ))
            )
            Oem.OTHER -> emptyList()
        }

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (ctx.packageManager.resolveActivity(intent, 0) != null) {
                    ctx.startActivity(intent)
                    return true
                }
            } catch (_: Exception) { }
        }
        return false
    }
}
