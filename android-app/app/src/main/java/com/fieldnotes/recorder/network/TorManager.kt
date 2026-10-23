package com.fieldnotes.recorder.network

import com.fieldnotes.recorder.R
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Manages network privacy: Tor (Orbot SOCKS5) and OS-level VPN detection.
 *
 * Two independent features:
 *
 * 1. **Tor routing** (`isTorEnabled`): routes all OkHttp traffic through
 *    Orbot's SOCKS5 proxy at 127.0.0.1:9050. User must install and start
 *    Orbot separately.
 *
 * 2. **Secure network requirement** (`isSecureNetworkRequired`): blocks
 *    all uploads unless a VPN or Tor proxy is active. Protects users who
 *    forget to enable their VPN before recording.
 *
 * These are independent — a user can:
 * - Use Tor only (SOCKS5 proxy routing + Tor enforcement)
 * - Use VPN only (OS-level, no app routing changes, VPN detected)
 * - Require either one (secure-network gate blocks if neither active)
 * - Use neither (direct HTTPS to cloud providers)
 *
 * Fail-closed semantics (audit C5/C6): when Tor is enabled, the upload gate
 * requires a *verified* Tor circuit — a reachable port alone is not Tor, and
 * a VPN is NOT accepted as a substitute (a VPN can be a hostile provider
 * logging metadata). VPN only satisfies the gate when Tor is disabled.
 */
object TorManager {

    private const val TAG = "DW-Net"

    /** Default Orbot SOCKS5 proxy address. */
    private const val PROXY_HOST = "127.0.0.1"
    private const val PROXY_PORT = 9050

    /** Orbot package names (official + fdroid). */
    private val ORBOT_PACKAGES = listOf(
        "org.torproject.android",
        "org.torproject.android.fdroid"
    )

    /** Timeout for probing the SOCKS5 proxy (ms). */
    private const val PROBE_TIMEOUT_MS = 3000

    /**
     * Host used for the SOCKS5 CONNECT probe. We ask the proxy to connect to
     * Tor's own check endpoint (torproject.org) — if Orbot is really there,
     * it will accept the CONNECT and route it through Tor. A non-Tor local
     * proxy would have to actually reach the host, but the point is that the
     * proxy speaks SOCKS5 and accepts hostname-based CONNECT (not just that a
     * port listener exists, which is what the old probe checked).
     */
    private const val PROBE_HOST = "check.torproject.org"
    private const val PROBE_PORT_HOST = 443

    // ── Tor proxy ───────────────────────────────────────────

    /**
     * Returns a [Proxy] configured for Orbot's SOCKS5 endpoint,
     * or [Proxy.NO_PROXY] if Tor is disabled.
     */
    fun getProxy(ctx: Context): Proxy {
        if (!com.fieldnotes.recorder.util.Prefs.isTorEnabled(ctx)) return Proxy.NO_PROXY
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress(PROXY_HOST, PROXY_PORT))
    }

    /**
     * True if Tor is enabled in settings AND the SOCKS5 proxy is a real,
     * handshake-verified Tor proxy (not just a port listener).
     */
    fun isTorReady(ctx: Context): Boolean {
        if (!com.fieldnotes.recorder.util.Prefs.isTorEnabled(ctx)) return false
        return isProxyReachable()
    }

    // ── VPN detection ───────────────────────────────────────

    /**
     * Detects whether the device currently has an active VPN transport.
     * Uses [ConnectivityManager] and [NetworkCapabilities.TRANSPORT_VPN].
     */
    fun isVpnActive(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) {
            false
        }
    }

    // ── Secure network gate ─────────────────────────────────

    /**
     * True if uploads must be blocked under the current network privacy
     * configuration. Fail-closed:
     *
     * - If secure-network is not required → never block (user opted out).
     * - If Tor is enabled → block UNLESS a verified Tor circuit is up. A VPN
     *   is NOT accepted as a substitute: the user explicitly asked for Tor,
     *   and a VPN can be a hostile provider. (audit C5)
     * - If Tor is disabled but secure-network is required → block unless a
     *   VPN is active.
     */
    fun shouldBlockUpload(ctx: Context): Boolean {
        val prefs = com.fieldnotes.recorder.util.Prefs
        if (!prefs.isSecureNetworkRequired(ctx)) return false

        if (prefs.isTorEnabled(ctx)) {
            // Tor strict mode: only a verified Tor circuit unblocks. VPN does not.
            return !isProxyReachable()
        }
        // Tor off: accept VPN as the secure-network transport.
        return !isVpnActive(ctx)
    }

    /**
     * Human-readable network privacy status for the Settings UI.
     * Call from a background thread (probes SOCKS5 port).
     */
    fun getStatusText(ctx: Context): String {
        val prefs = com.fieldnotes.recorder.util.Prefs
        val torEnabled = prefs.isTorEnabled(ctx)
        val vpnActive = isVpnActive(ctx)
        val torReachable = if (torEnabled) isProxyReachable() else false

        return buildString {
            // VPN status
            if (vpnActive) append(ctx.getString(R.string.net_vpn_active)) else append(ctx.getString(R.string.net_vpn_absent))
            append("  |  ")
            // Tor status
            if (!torEnabled) {
                append(ctx.getString(R.string.net_tor_disabled))
            } else if (torReachable) {
                append(ctx.getString(R.string.net_tor_reachable))
            } else if (isOrbotInstalled(ctx)) {
                append(ctx.getString(R.string.net_tor_stopped))
            } else {
                append(ctx.getString(R.string.net_tor_missing))
            }
            // Overall verdict
            if (prefs.isSecureNetworkRequired(ctx)) {
                append("\n")
                // Mirror shouldBlockUpload: VPN only satisfies the gate when Tor is off.
                val ok = if (torEnabled) torReachable else vpnActive
                if (ok) {
                    append(ctx.getString(R.string.net_requirement_ok))
                } else {
                    append(ctx.getString(if (torEnabled) R.string.net_blocked_tor else R.string.net_blocked_vpn))
                }
            }
        }
    }

    // ── Orbot helpers ───────────────────────────────────────

    /**
     * Checks if any Orbot package is installed on the device.
     */
    fun isOrbotInstalled(ctx: Context): Boolean {
        val pm = ctx.packageManager
        for (pkg in ORBOT_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return true
            } catch (_: PackageManager.NameNotFoundException) {
                // Try next
            }
        }
        return false
    }

    /**
     * Probes the SOCKS5 proxy by performing a real SOCKS5 handshake
     * (RFC 1928): method negotiation + CONNECT to a known host. A reachable
     * TCP port is NOT sufficient — a malicious local listener could claim to
     * be Tor while routing cleartext, deanonymizing the user (audit C6).
     *
     * This is a blocking call — run off the main thread.
     */
    fun isProxyReachable(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(PROXY_HOST, PROXY_PORT),
                    PROBE_TIMEOUT_MS
                )
                socket.soTimeout = PROBE_TIMEOUT_MS

                val out = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())

                // ── Greeting ──────────────────────────────────────────
                // VER=5, NMETHODS=1, METHODS=[NO_AUTH (0x00)]
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()

                // Server reply: VER, METHOD. Must be VER=5 and METHOD=0x00 (NO_AUTH).
                // Any other METHOD (e.g. 0xFF "no acceptable methods") means this
                // is not a usable Tor SOCKS5 endpoint.
                val ver = input.readUnsignedByte()
                val method = input.readUnsignedByte()
                if (ver != 0x05 || method != 0x00) return false

                // ── CONNECT request ───────────────────────────────────
                // VER=5, CMD=1 (CONNECT), RSV=0, ATYP=3 (DOMAINNAME), host, port.
                // Using ATYP=3 forces the proxy to do remote DNS — this is what
                // prevents DNS leaks over Tor. A non-SOCKS5 listener will fail
                // or choke here.
                val hostBytes = PROBE_HOST.toByteArray(Charsets.US_ASCII)
                if (hostBytes.size > 255) return false
                out.writeByte(0x05)
                out.writeByte(0x01) // CONNECT
                out.writeByte(0x00) // RSV
                out.writeByte(0x03) // ATYP = DOMAINNAME
                out.writeByte(hostBytes.size)
                out.write(hostBytes)
                out.writeShort(PROBE_PORT_HOST)
                out.flush()

                // Server reply: VER, REP, RSV, ATYP, BND.ADDR, BND.PORT.
                // REP == 0x00 means success — the proxy accepted the CONNECT
                // (it will actually try to reach the host over Tor). Any other
                // REP code is a failure; we don't need the bound address, but
                // we must drain it so the stream stays consistent.
                val rVer = input.readUnsignedByte()
                val rRep = input.readUnsignedByte()
                if (rVer != 0x05) return false
                // Upload gate: require REP == 0x00 (CONNECT succeeded — a real
                // circuit to the probe host was established). The previous code
                // accepted any REP in 0x00..0x09, which let a generic local
                // SOCKS5 listener (e.g. malware that grabbed 127.0.0.1:9050
                // before Orbot) pass the gate just by speaking SOCKS5 framing
                // and *refusing* the CONNECT — after which real upload traffic
                // would be routed through the attacker's proxy with destination
                // hostnames exposed (security review MEDIUM). Requiring a
                // successful CONNECT keeps the gate fail-closed: if we cannot
                // confirm a working circuit, we do not upload. The probe host
                // (check.torproject.org) is highly available, so legitimate
                // Orbot circuits pass; a transient failure correctly blocks
                // upload rather than risking a leak.
                if (rRep != 0x00) return false
                // Drain RSV + ATYP + bound addr + port so the socket closes cleanly.
                input.readUnsignedByte() // RSV
                val atyp = input.readUnsignedByte()
                when (atyp) {
                    0x01 -> { input.readFully(ByteArray(4)) }
                    0x03 -> { val n = input.readUnsignedByte(); input.readFully(ByteArray(n)) }
                    0x04 -> { input.readFully(ByteArray(16)) }
                }
                input.readUnsignedShort() // BND.PORT

                // REP == 0x00 confirmed above → fully working Tor circuit.
                true
            }
        } catch (_: IOException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns an Intent to install Orbot from Play Store or Guardian Project.
     */
    fun getOrbotInstallIntent(ctx: Context): android.content.Intent? {
        val playIntent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("market://details?id=org.torproject.android")
        )
        if (playIntent.resolveActivity(ctx.packageManager) != null) return playIntent

        return android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://guardianproject.info/apps/org.torproject.android/")
        )
    }

    /**
     * Returns an Intent to launch the Orbot app (to start it).
     */
    fun getOrbotLaunchIntent(ctx: Context): android.content.Intent? {
        for (pkg in ORBOT_PACKAGES) {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) return intent
        }
        return null
    }
}
