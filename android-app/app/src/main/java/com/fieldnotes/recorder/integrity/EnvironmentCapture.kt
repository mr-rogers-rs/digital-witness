package com.fieldnotes.recorder.integrity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.fieldnotes.recorder.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Captures environmental metadata at the moment a segment is finalized.
 * This data is hard to fabricate retroactively and strengthens forensic provenance:
 *
 * - Device fingerprint (model, OS, build)
 * - Network state (type, cell tower IDs if available)
 * - Battery level + charging state
 * - Sensor snapshots (accelerometer, gyroscope, barometer, light, magnetic field)
 *
 * All data is read-only and uses no dangerous permissions beyond what the app
 * already has. Location (GPS) is NOT collected unless the user explicitly grants
 * ACCESS_FINE_LOCATION — this is intentional for anonymity.
 */
object EnvironmentCapture {

    private const val TAG = "DW-EnvCapture"

    /**
     * Per-sensor capture timeout. Sensors deliver at ~5Hz under
     * SENSOR_DELAY_NORMAL, so 150ms is enough for at least one reading; keeping
     * this short bounds the segment-finalize path (audit H7). Sensors are
     * captured in parallel, so the total wait is ~this, not N×this.
     */
    private const val SENSOR_CAPTURE_TIMEOUT_MS = 150L

    /**
     * Capture environment metadata.
     *
     * @param anonymous When true (Anonymous Mode), strips device-identifying fields
     *   (manufacturer, model, build fingerprint, network operator, SIM info,
     *   locale/timezone, network transport type) and forces GPS off. Motion/magnetic
     *   sensors are also dropped — their calibration bias is a hardware fingerprint.
     *   Only environmental sensors (barometer, light) and battery state are kept.
     */
    fun capture(context: Context, anonymous: Boolean = false): JSONObject {
        val env = JSONObject()

        if (anonymous) {
            env.put("mode", "anonymous")
            // Minimal device info: only OS version (common across millions of devices)
            try {
                env.put("device", JSONObject().apply {
                    put("androidVersion", Build.VERSION.RELEASE)
                    put("sdkInt", Build.VERSION.SDK_INT)
                })
            } catch (e: Exception) { Log.w(TAG, "device capture failed", e) }
            // Network: only connection type, no operator/SIM
            try {
                env.put("network", captureNetworkAnonymous(context))
            } catch (e: Exception) { Log.w(TAG, "network capture failed", e) }
        } else {
            env.put("mode", "evidence")
            try { env.put("device", captureDevice()) } catch (e: Exception) { Log.w(TAG, "device capture failed", e) }
            try { env.put("network", captureNetwork(context)) } catch (e: Exception) { Log.w(TAG, "network capture failed", e) }
            try { env.put("locale", captureLocale()) } catch (e: Exception) { Log.w(TAG, "locale capture failed", e) }
            if (Prefs.isGpsEnabled(context)) {
                try { env.put("location", captureLocation(context)) } catch (e: Exception) { Log.w(TAG, "location capture failed", e) }
            }
        }

        // Battery is safe in both modes. Sensors are mode-aware: motion/magnetic
        // sensors are dropped in anonymous mode (calibration bias = device fingerprint).
        try { env.put("battery", captureBattery(context)) } catch (e: Exception) { Log.w(TAG, "battery capture failed", e) }
        try { env.put("sensors", captureSensors(context, anonymous)) } catch (e: Exception) { Log.w(TAG, "sensor capture failed", e) }

        return env
    }

    private fun captureDevice(): JSONObject {
        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("brand", Build.BRAND)
            put("device", Build.DEVICE)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("buildFingerprint", Build.FINGERPRINT)
            put("bootloader", Build.BOOTLOADER)
            put("hardware", Build.HARDWARE)
        }
    }

    /** The active network's capabilities, or null if offline/unavailable. */
    private fun activeNetworkCapabilities(context: Context): NetworkCapabilities? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Anonymous-safe network info: presence only. The transport type is omitted so
     * we never reveal VPN usage (or wifi/cellular, which is mildly fingerprinting);
     * operator/SIM identifiers are never read in this mode.
     */
    private fun captureNetworkAnonymous(context: Context): JSONObject {
        return JSONObject().apply { put("connected", activeNetworkCapabilities(context) != null) }
    }

    private fun captureNetwork(context: Context): JSONObject {
        val net = JSONObject()
        val caps = activeNetworkCapabilities(context)
        if (caps != null) {
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            net.put("type", type)
            net.put("connected", true)
        } else {
            net.put("type", "none")
            net.put("connected", false)
        }

        // Cell tower info (doesn't require location permission for basic info).
        // dataNetworkType is intentionally NOT read: it requires READ_PHONE_STATE on
        // API 30+ (which we deliberately don't declare, for anonymity), so it was
        // permanently "permission_denied" — a useless field. Dropped (review #8).
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            net.put("networkOperator", tm.networkOperatorName ?: "unknown")
            net.put("networkCountryIso", tm.networkCountryIso ?: "unknown")
            net.put("simOperator", tm.simOperatorName ?: "unknown")
            net.put("simCountryIso", tm.simCountryIso ?: "unknown")
            net.put("phoneType", when (tm.phoneType) {
                TelephonyManager.PHONE_TYPE_GSM -> "GSM"
                TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
                TelephonyManager.PHONE_TYPE_SIP -> "SIP"
                else -> "none"
            })
        } catch (e: SecurityException) {
            net.put("cellInfo", "permission_denied")
        } catch (e: Exception) {
            net.put("cellInfo", "error")
        }

        return net
    }

    private fun captureBattery(context: Context): JSONObject {
        val batt = JSONObject()
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            batt.put("level", level)
            batt.put("charging", charging)
        } catch (e: Exception) {
            batt.put("error", e.message)
        }
        return batt
    }

    private fun captureSensors(context: Context, anonymous: Boolean): JSONObject {
        val sensors = JSONObject()
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Build the list of sensors to capture for this mode.
        data class SensorReq(val type: Int, val name: String)
        val reqs = mutableListOf<SensorReq>()
        // Environmental sensors corroborate the physical surroundings without
        // identifying the device — safe in both modes.
        reqs.add(SensorReq(Sensor.TYPE_PRESSURE, "barometer"))
        reqs.add(SensorReq(Sensor.TYPE_LIGHT, "light"))
        // Motion/magnetic sensors carry device-specific calibration biases that act as
        // a persistent hardware fingerprint — include them only in evidence mode.
        if (!anonymous) {
            reqs.add(SensorReq(Sensor.TYPE_ACCELEROMETER, "accelerometer"))
            reqs.add(SensorReq(Sensor.TYPE_GYROSCOPE, "gyroscope"))
            reqs.add(SensorReq(Sensor.TYPE_MAGNETIC_FIELD, "magneticField"))
        }

        // Capture all requested sensors IN PARALLEL (audit H7). The previous
        // implementation captured them sequentially, each blocking up to 500ms
        // on a CountDownLatch — worst case ~2.5s on the segment-finalize path,
        // stalling the muxer and risking dropped frames / a stalled recording.
        // Parallel capture with a short per-sensor timeout brings the worst
        // case down to a single ~150ms wait. Sensors deliver at ~5Hz under
        // SENSOR_DELAY_NORMAL, so 150ms is enough for at least one reading.
        val results = Array(reqs.size) { Pair<String, FloatArray?>(reqs[it].name, null) }
        val threads = Array<Thread?>(reqs.size) { null }
        for (i in reqs.indices) {
            val req = reqs[i]
            threads[i] = Thread {
                results[i] = req.name to captureSingleSensorBlocking(sm, req.type)
            }.apply { isDaemon = true; name = "dw-sensor-$i" }
            threads[i]!!.start()
        }
        // Join with a bounded total wait so a stuck sensor can't stall finalize.
        for (t in threads) {
            try { t!!.join(SENSOR_CAPTURE_TIMEOUT_MS + 50) } catch (_: Exception) {}
        }
        for ((name, values) in results) {
            if (values != null) {
                val arr = JSONArray()
                for (v in values) arr.put(v.toDouble())
                sensors.put(name, arr)
            }
        }
        return sensors
    }

    private fun captureSingleSensorBlocking(sm: SensorManager, type: Int): FloatArray? {
        val sensor = sm.getDefaultSensor(type) ?: return null
        val latch = CountDownLatch(1)
        var values: FloatArray? = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                values = event.values.clone()
                latch.countDown()
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        try {
            latch.await(SENSOR_CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            sm.unregisterListener(listener)
        }
        return values
    }

    private fun captureLocale(): JSONObject {
        return JSONObject().apply {
            val locale = java.util.Locale.getDefault()
            put("language", locale.language)
            put("country", locale.country)
            put("timezone", java.util.TimeZone.getDefault().id)
            put("timezoneOffset", java.util.TimeZone.getDefault().rawOffset / 60000) // minutes
        }
    }

    @Suppress("MissingPermission") // caller checks permission via Prefs.isGpsEnabled + runtime check
    private fun captureLocation(context: Context): JSONObject {
        val loc = JSONObject()
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            loc.put("error", "permission_not_granted")
            return loc
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Try to get the last known location from any provider
        var bestLocation: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(provider)
                if (l != null && (bestLocation == null || l.accuracy < bestLocation!!.accuracy)) {
                    bestLocation = l
                }
            } catch (_: Exception) {}
        }

        if (bestLocation != null) {
            loc.put("latitude", bestLocation.latitude)
            loc.put("longitude", bestLocation.longitude)
            loc.put("accuracy", bestLocation.accuracy.toDouble())
            loc.put("altitude", bestLocation.altitude)
            loc.put("provider", bestLocation.provider ?: "unknown")
            loc.put("ageMs", System.currentTimeMillis() - bestLocation.time)
            if (bestLocation.hasBearing()) loc.put("bearing", bestLocation.bearing.toDouble())
            if (bestLocation.hasSpeed()) loc.put("speed", bestLocation.speed.toDouble())
        } else {
            loc.put("error", "no_location_available")
        }

        return loc
    }
}
