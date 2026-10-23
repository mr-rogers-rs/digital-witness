package com.fieldnotes.recorder.network

import android.content.Context
import okhttp3.CertificatePinner
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Proxy

/**
 * Factory for OkHttpClient instances with certificate pinning and Tor-safe DNS.
 *
 * Pins both the leaf and intermediate CA public keys (SHA-256 SPKI hashes)
 * for every known service. OkHttp requires at least one pin to match the
 * chain — pinning the intermediate gives stability across leaf rotations,
 * while the leaf pin adds defense if an intermediate CA is compromised.
 *
 * When a pin check fails, OkHttp throws [javax.net.ssl.SSLPeerUnverifiedException]
 * with details about expected vs. actual pins, making it obvious if an
 * attacker is MITM-ing or if a pin needs rotation.
 *
 * For user-supplied hosts (WebDAV), pinning is not possible — those clients
 * rely on the system trust store only.
 *
 * **DNS leak prevention:** OkHttp's default Dns resolves hostnames via the
 * system resolver BEFORE the SOCKS5 proxy, leaking destination hostnames
 * to the local ISP even when Tor is active. When a SOCKS proxy is in use,
 * we install a custom [Dns] that returns a sentinel address; OkHttp then
 * passes the hostname to the SOCKS5 proxy for remote resolution at the
 * exit node.
 *
 * **No-context overloads removed:** every network client must be created
 * with a Context so the user's Tor/VPN preferences and DNS routing apply.
 *
 * PIN ROTATION: when a provider rotates certificates, rebuild the app with
 * fresh pins obtained via:
 *   openssl s_client -connect HOST:443 -servername HOST </dev/null 2>/dev/null \
 *     | openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER \
 *     | openssl dgst -sha256 -binary | openssl enc -base64
 * Then repeat with -showcerts and pick the second certificate for the
 * intermediate pin.
 */
object PinnedHttpClient {
    private val activeCalls = java.util.concurrent.CopyOnWriteArraySet<okhttp3.Call>()

    fun cancelAll() { activeCalls.forEach { it.cancel() } }

    private fun baseBuilder(ctx: Context): OkHttpClient.Builder {
        val torAtCreation = com.fieldnotes.recorder.util.Prefs.isTorEnabled(ctx)
        fun checkPolicy() {
            com.fieldnotes.recorder.upload.DeliveryPolicy.checkActiveDestination(ctx)
            if (torAtCreation != com.fieldnotes.recorder.util.Prefs.isTorEnabled(ctx) ||
                com.fieldnotes.recorder.util.PanicWipe.wipeInProgress || TorManager.shouldBlockUpload(ctx)) {
                throw java.io.IOException("Network policy blocks this request")
            }
        }
        return OkHttpClient.Builder()
            .eventListener(object : okhttp3.EventListener() {
                override fun callStart(call: okhttp3.Call) { activeCalls.add(call) }
                override fun callEnd(call: okhttp3.Call) { activeCalls.remove(call) }
                override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { activeCalls.remove(call) }
            })
            .addInterceptor { chain ->
                checkPolicy()
                if (!chain.request().url.isHttps) throw java.io.IOException("HTTPS required")
                chain.proceed(chain.request())
            }
            .addNetworkInterceptor { chain -> checkPolicy(); chain.proceed(chain.request()) }
            .followRedirects(false)
            .followSslRedirects(false)
    }

    // ── Dropbox ─────────────────────────────────────────────
    private const val DROPBOX_API_LEAF         = "sha256/lkg3r1httRHu/VSIW0LzO3O4MLSswlBdNnkn3ab7sg4="
    private const val DROPBOX_API_INTERMEDIATE  = "sha256/qBRjZmOmkSNJL0p70zek7odSIzqs/muR4Jk9xYyCP+E="
    private const val DROPBOX_CONTENT_LEAF      = "sha256/2pQ+aTDZX5aLeK2UJoVU9XLe4HU0iaIz8Y5pcHfykqw="

    // ── Google ──────────────────────────────────────────────
    // Google-published GTS root SPKIs, verified from
    // https://pki.goog/roots.pem. Leaf/intermediate keys rotate.
    // Platform chain validation and hostname verification still apply first.
    private val googleRootPins = arrayOf(
        "sha256/hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=", // GTS Root R1
        "sha256/Vfd95BwDeSQo+NUYxVEEIlvkOlWY2SalKK1lPhzOx78=", // GTS Root R2
        "sha256/QXnt2YHvdHR3tJYmQIr0Paosp6t/nggsEGD4QJZ3Q0g=", // GTS Root R3
        "sha256/mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c="  // GTS Root R4
    )

    // ── OpenTimestamps ──────────────────────────────────────
    private const val OTS_LEAF                 = "sha256/9Fnjmp2Vwa/4iXHZu4n5aALzQrXmaaAZ2j0lt2Il3NM="
    private const val OTS_INTERMEDIATE          = "sha256/y7xVm0TVJNahMr2sZydE2jQH8SquXV9yLF9seROHHHU="

    // ── Mega ────────────────────────────────────────────────
    private const val MEGA_LEAF                = "sha256/0W38e765pAfPqS3DqSVOrPsC4MEOvRBaXQ7nY1AJ47E="
    private const val MEGA_INTERMEDIATE         = "sha256/a9khLOZJxlnJyrxstg/P+seiDCm+Yf3OsrXyFocBaI0="

    private val pinner: CertificatePinner = CertificatePinner.Builder()
        .add("api.dropboxapi.com",     DROPBOX_API_LEAF, DROPBOX_API_INTERMEDIATE)
        .add("content.dropboxapi.com", DROPBOX_CONTENT_LEAF, DROPBOX_API_INTERMEDIATE)
        .add("www.googleapis.com",     *googleRootPins)
        .add("oauth2.googleapis.com",  *googleRootPins)
        .add("a.pool.opentimestamps.org", OTS_LEAF, OTS_INTERMEDIATE)
        .add("b.pool.opentimestamps.org", OTS_LEAF, OTS_INTERMEDIATE)
        .add("a.pool.eternitywall.com",   OTS_LEAF, OTS_INTERMEDIATE)
        .add("g.api.mega.co.nz",      MEGA_LEAF, MEGA_INTERMEDIATE)
        .build()

    /**
     * A [Dns] that returns an **empty list**, forcing fail-closed behavior.
     *
     * This is the OkHttp maintainers' recommended pattern for Tor-like threat
     * models (square/okhttp#4430, #9390): when a SOCKS proxy is configured,
     * OkHttp does NOT consult [Dns.lookup] for the target host at all — it
     * builds `InetSocketAddress.createUnresolved(host, port)` and hands the
     * hostname to the SOCKS5 proxy for remote resolution at the exit node,
     * which is exactly what we want (no local DNS leak).
     *
     * The previous implementation returned a sentinel `0.0.0.0` address,
     * which is fragile: if any code path ever DID resolve through this DNS
     * (a future OkHttp change, a misconfigured proxy, or a library that
     * pre-resolves), OkHttp would hand `0.0.0.0` to the SOCKS proxy as a
     * literal IP, causing silent connection failures or unpredictable
     * behavior (audit H1).
     *
     * Returning empty list is strictly safer:
     *  - SOCKS path: lookup is never called, so this is a no-op (hostname
     *    still reaches the proxy for remote DNS — leak-free).
     *  - Any other path: lookup returns nothing → `UnknownHostException`,
     *    failing closed instead of leaking or misrouting.
     *
     * Only installed when a SOCKS proxy is actually configured on the client.
     */
    private val socksRemoteDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = emptyList()
    }

    /**
     * Returns a new OkHttpClient.Builder pre-configured with certificate
     * pinning, Tor SOCKS5 proxy routing (if enabled), and SOCKS-remote DNS
     * resolution to prevent DNS leaks. Callers can further customize
     * timeouts, interceptors, etc. before calling .build().
     */
    fun pinnedBuilder(ctx: Context): OkHttpClient.Builder {
        val proxy = TorManager.getProxy(ctx)
        val builder = baseBuilder(ctx).certificatePinner(pinner).proxy(proxy)
        if (proxy.type() == Proxy.Type.SOCKS) {
            builder.dns(socksRemoteDns)
        }
        return builder
    }

    /**
     * Returns an OkHttpClient.Builder WITHOUT certificate pinning, but still
     * routed through Tor (when enabled) with SOCKS-remote DNS. Use only for
     * user-specified hosts (e.g., WebDAV) where pins cannot be known at
     * compile time. The system trust store is still used for TLS validation.
     */
    fun unpinnedBuilder(ctx: Context): OkHttpClient.Builder {
        val proxy = TorManager.getProxy(ctx)
        val builder = baseBuilder(ctx).proxy(proxy)
        if (proxy.type() == Proxy.Type.SOCKS) {
            builder.dns(socksRemoteDns)
        }
        return builder
    }

    /**
     * Like [unpinnedBuilder] but pins a single user-specified [host] to a
     * user-supplied SHA-256 SPKI fingerprint. For self-hosted servers
     * (e.g. Nextcloud) whose certificate is unknown at compile time: the user
     * obtains the fingerprint out-of-band (or via the in-app "fetch & show"
     * helper on a trusted network) and pins it, defeating state-CA MITM that
     * the system trust store would otherwise accept.
     *
     * [spkiSha256] is accepted with or without the OkHttp "sha256/" prefix.
     * Returns an unpinned builder if the host/pin is blank.
     */
    fun pinnedBuilderForHost(ctx: Context, host: String, spkiSha256: String): OkHttpClient.Builder {
        val builder = unpinnedBuilder(ctx)
        if (host.isBlank() || spkiSha256.isBlank()) return builder
        val pin = if (spkiSha256.startsWith("sha256/")) spkiSha256 else "sha256/$spkiSha256"
        val hostPinner = CertificatePinner.Builder().add(host, pin).build()
        return builder.certificatePinner(hostPinner)
    }
}
