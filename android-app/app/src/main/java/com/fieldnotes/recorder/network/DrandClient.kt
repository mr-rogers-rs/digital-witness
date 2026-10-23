package com.fieldnotes.recorder.network

import android.content.Context
import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches the latest value from the **drand** distributed randomness beacon
 * (League of Entropy). drand emits a signed, public, immutable random value every ~30 s;
 * a round's value is unpredictable before that round's time.
 *
 * Folding the beacon randomness into the chain seed (H_0) establishes a cryptographic
 * **lower** time bound: the recording could not have been produced before that round
 * existed — rebutting "this is old / pre-rendered / pre-generated footage." It complements
 * the OpenTimestamps **upper** bound ("existed before Bitcoin block N"), bracketing the
 * capture time between two independent public systems with no trust in the device clock.
 *
 * Verification: the round number and randomness are public and immutable, so a verifier
 * re-fetches the round from any drand relay and confirms the recorded randomness matches.
 *
 * Routed through Tor when enabled (drand relays are not in the pin set, so standard TLS
 * via the system trust store applies — still proxied + SOCKS-remote DNS).
 */
object DrandClient {

    private const val TAG = "DW-Drand"

    /** Public HTTP relays for the default League-of-Entropy chain. */
    private val RELAYS = listOf(
        "https://api.drand.sh",
        "https://api2.drand.sh",
        "https://api3.drand.sh",
        "https://drand.cloudflare.com"
    )

    data class Beacon(val round: Long, val randomness: String) {
        fun toJson(): String = JSONObject().apply {
            put("source", "drand")
            put("round", round)
            put("randomness", randomness)
        }.toString()
    }

    /**
     * Fetch the latest beacon, trying relays in order. Returns null if all fail — the
     * caller then proceeds without a lower time bound (degraded, recorded in the proof).
     * Bounded timeouts keep this from stalling the start of a recording.
     */
    fun fetchLatest(ctx: Context): Beacon? {
        val client = PinnedHttpClient.pinnedBuilder(ctx)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()

        for (relay in RELAYS) {
            try {
                val req = Request.Builder()
                    .url("$relay/public/latest")
                    .header("User-Agent", "okhttp/4.12.0")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (body != null) {
                            val o = JSONObject(body)
                            val round = o.optLong("round", -1L)
                            val rnd = o.optString("randomness", "")
                            if (round > 0 && rnd.isNotEmpty()) {
                                Log.i(TAG, "drand beacon round=$round")
                                return Beacon(round, rnd)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "drand relay failed: ${e.message}")
            }
        }
        Log.w(TAG, "All drand relays unreachable — proceeding without lower time bound")
        return null
    }
}
