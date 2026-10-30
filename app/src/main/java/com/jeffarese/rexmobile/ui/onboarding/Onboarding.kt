package com.jeffarese.rexmobile.ui.onboarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostStore
import com.jeffarese.rexmobile.rex.ControlConnection
import com.jeffarese.rexmobile.rex.ControlListener
import com.jeffarese.rexmobile.rex.SessionList
import com.jeffarese.rexmobile.rex.Welcome
import com.jeffarese.rexmobile.rex.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/** Whether this phone has been through the welcome. */
class FirstRun(context: Context) {
    private val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    val done: Boolean get() = prefs.getBoolean("done", false)

    fun markDone() = prefs.edit().putBoolean("done", true).apply()

    /**
     * Show the welcome on a first launch with no hosts. A build with preset hosts, or a phone that had
     * hosts before the welcome existed, goes straight to Home and won't see it later either.
     */
    fun shouldShow(hosts: List<HostConfig>): Boolean {
        if (done) return false
        if (hosts.isNotEmpty()) {
            markDone()
            return false
        }
        return true
    }
}

sealed interface AddressCheck {
    data class Ok(val url: String) : AddressCheck
    data class Invalid(val message: String) : AddressCheck
}

/**
 * Accepts what the Mac shows, with or without https://, and a host:port. Plain http:// is only for
 * a tailnet or this network, as in Rex's own apps: a tailnet's traffic is encrypted underneath.
 */
fun checkAddress(input: String): AddressCheck {
    if (input.isBlank()) return AddressCheck.Invalid("Enter your Mac's Rex address")
    val url = runCatching { ControlConnection.normalizeHostUrl(input) }.getOrNull()
        ?: return AddressCheck.Invalid("That isn't an address. It looks like mac-rex.tail1234.ts.net")
    if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
        return AddressCheck.Invalid("Use an https:// address, such as https://mac-rex.tail1234.ts.net")
    }
    if (url.startsWith("http://", ignoreCase = true) && !isPrivateHost(hostOf(url))) {
        return AddressCheck.Invalid("Plain http:// works only for a Tailscale or local address. Use https:// for this one.")
    }
    return AddressCheck.Ok(url)
}

/** The host of a normalized URL, without an IPv6 address's brackets. */
internal fun hostOf(url: String): String = java.net.URI(url).host.orEmpty().removePrefix("[").removeSuffix("]").lowercase()

/**
 * A tailnet name, a MagicDNS short name, or an address on a tailnet or this network: 100.64.0.0/10
 * and fd7a:115c:a1e0::/48 are Tailscale's.
 */
internal fun isPrivateHost(host: String): Boolean {
    if (host.endsWith(".ts.net")) return true
    if (':' in host) return host == "::1" || host.startsWith("fd") || host.startsWith("fc") || host.startsWith("fe8")
    val ip = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""").matchEntire(host)
        ?: return '.' !in host
    val (a, b) = ip.destructured.toList().map { it.toInt() }
    return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
        (a == 169 && b == 254) || (a == 100 && b in 64..127)
}

/** The address in a scanned pairing code: `rex server tailscale pair` puts only the URL in it. */
fun addressFromCode(text: String): String? {
    val trimmed = text.trim()
    val url = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE).find(trimmed)?.value
    // A link that carries the address, such as one of Rex's own, rather than being it.
    val carried = runCatching { java.net.URI(trimmed).rawQuery }.getOrNull()
        ?.split('&')?.firstNotNullOfOrNull { p ->
            p.split('=', limit = 2).takeIf { it.size == 2 && it[0] in setOf("url", "address", "server", "host") }
                ?.let { java.net.URLDecoder.decode(it[1], "UTF-8") }
        }
    val candidate = carried ?: url ?: trimmed
    return (checkAddress(candidate) as? AddressCheck.Ok)?.url
}

/**
 * Scan a pairing code with Play services' scanner, which needs no camera permission. [onAddress] gets
 * the address in it, from [addressFromCode]; [onProblem] what to tell the user when there is none.
 * Cancelling the scan calls neither.
 */
fun scanPairingCode(context: Context, onAddress: (url: String) -> Unit, onProblem: (message: String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    runCatching {
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code ->
                val scanned = code.rawValue?.let(::addressFromCode)
                if (scanned == null) onProblem("That code has no Rex address in it. Use the one rex server tailscale pair shows.")
                else onAddress(scanned)
            }
            .addOnFailureListener { onProblem("Couldn't open the scanner: ${it.message}") }
    }.onFailure { onProblem("Couldn't open the scanner on this phone") }
}

/** A Tailscale address, which needs Tailscale on this phone to reach. */
fun isTailnetAddress(url: String): Boolean {
    val host = hostOf(url)
    val ip = Regex("""100\.(\d{1,3})\.\d{1,3}\.\d{1,3}""").matchEntire(host)
    return host.endsWith(".ts.net") || host.startsWith("fd7a:115c:a1e0:") || (ip != null && ip.groupValues[1].toInt() in 64..127)
}

/** Whether Tailscale can carry this phone's connections. */
enum class Tailscale { MISSING, OFF, ON }

const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

/** Installed, and with a VPN up, which on a phone with Tailscale is as good as saying it is connected. */
fun tailscaleState(context: Context): Tailscale {
    val installed = runCatching { context.packageManager.getPackageInfo(TAILSCALE_PACKAGE, 0) }.isSuccess
    if (!installed) return Tailscale.MISSING
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val vpn = connectivity?.allNetworks.orEmpty().any {
        connectivity?.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
    return if (vpn) Tailscale.ON else Tailscale.OFF
}

/** Why a connection test failed, as far as the error says. */
enum class Problem { NOT_FOUND, NO_ANSWER, TURNED_AWAY, NOT_SECURE, NOT_REX, PROXY_ERROR, OTHER }

/** OkHttp's "Expected HTTP 101 response but was '502 Bad Gateway'" → "502 Bad Gateway". */
internal fun httpStatus(reason: String?): String? =
    reason?.let { Regex("""but was '(\d{3}[^']*)'""").find(it)?.groupValues?.get(1)?.trim() }

/** Reads OkHttp's and the control connection's error messages; they carry no other detail. */
fun classify(reason: String?): Problem {
    val r = reason.orEmpty().lowercase()
    return when {
        listOf("unable to resolve host", "no address associated", "nodename nor servname", "name or service not known", "unknownhost")
            .any { it in r } -> Problem.NOT_FOUND
        // OkHttp: "Expected HTTP 101 response but was '403 Forbidden'".
        "'403" in r -> Problem.TURNED_AWAY
        // A proxy in front of Rex, such as Tailscale Serve while Rex isn't running, answers for it.
        httpStatus(reason)?.firstOrNull() == '5' -> Problem.PROXY_ERROR
        "expected http 101" in r || "unexpected protocol" in r -> Problem.NOT_REX
        listOf("ssl", "certificate", "trust anchor", "chain validation", "handshake", "not verified")
            .any { it in r } -> Problem.NOT_SECURE
        listOf("failed to connect", "refused", "timed out", "timeout", "unreachable", "no route to host", "abandoned")
            .any { it in r } -> Problem.NO_ANSWER
        else -> Problem.OTHER
    }
}

/** What to tell the user, in terms of what they can change on the phone or the Mac. */
fun hint(problem: Problem, url: String, reason: String?): String {
    val host = url.substringAfter("://").substringBefore(':')
    return when (problem) {
        Problem.NOT_FOUND -> "Can't find $host. Check the address, and that Tailscale is connected on this phone."
        Problem.NO_ANSWER -> "$host didn't answer. Check that Tailscale is connected on this phone, and that Rex on your Mac is serving over Tailscale."
        Problem.TURNED_AWAY -> "Rex turned this phone away. Sign in to Tailscale here with the Mac's account, " +
            "or on the Mac turn off Limit access to your Tailscale account in Rex's Server settings."
        Problem.NOT_SECURE ->
            if ('.' !in host) "Use the full address, ending in .ts.net."
            else "Couldn't make a secure connection. If you just turned on serving, Rex may still be getting its certificate: try again in a minute."
        Problem.NOT_REX -> "Something answered at $host" + (httpStatus(reason)?.let { " ($it)" } ?: "") +
            ", but it isn't a Rex server. Check the address."
        Problem.PROXY_ERROR -> "$host answered ${httpStatus(reason)} from in front of Rex. Check that Rex is running on the Mac, " +
            "then try again."
        Problem.OTHER -> "Couldn't connect" + (reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
    }
}

sealed interface Probe {
    data object Idle : Probe
    data object Testing : Probe
    /** The address can't be used, so nothing was tried. */
    data class Invalid(val message: String) : Probe
    data class Connected(val host: HostConfig, val version: String?, val sessions: Int) : Probe
    data class Failed(val url: String, val problem: Problem, val detail: String?) : Probe {
        val hint get() = hint(problem, url, detail)
    }
    /** A tailnet address, while Tailscale isn't on here: nothing was tried. */
    data class NeedsTailscale(val url: String, val state: Tailscale) : Probe
}

/** Open a control connection the way the app does, ask for the version and sessions, then leave. */
suspend fun testConnection(http: OkHttpClient, url: String, timeoutMs: Long = 15_000): Probe {
    val opened = CompletableDeferred<String?>()
    val conn = ControlConnection(http, url, object : ControlListener {
        override fun onWelcome(welcome: Welcome) { opened.complete(null) }
        override fun onEvent(seq: Long, event: JsonObject) {}
        override fun onClosed(code: Int, reason: String) { opened.complete(reason.ifBlank { "connection closed ($code)" }) }
    }, reconnectToken = null)
    return try {
        val closed = withTimeout(timeoutMs) { opened.await() }
        if (closed != null) return Probe.Failed(url, classify(closed), closed)
        val version = (conn.call("server.version") as? JsonObject)?.str("rex_version")
        val sessions = conn.callAs<SessionList>("session.list").sessions.orEmpty().size
        Probe.Connected(HostConfig(label = HostStore.hostLabelFrom(url), url = url), version, sessions)
    } catch (e: TimeoutCancellationException) {
        Probe.Failed(url, Problem.NO_ANSWER, "no answer in ${timeoutMs / 1000}s")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Probe.Failed(url, classify(e.message), e.message)
    } finally {
        conn.close()
    }
}

/**
 * The add-host step: check the address, test it, and keep the host once it answers. A host that
 * doesn't answer is kept only when the user asks, with [addAnyway].
 */
class AddHostStep(
    private val store: HostStore,
    private val tailscale: () -> Tailscale = { Tailscale.ON },
    private val test: suspend (url: String) -> Probe,
) {
    suspend fun connect(address: String, label: String): Probe {
        val url = when (val check = checkAddress(address)) {
            is AddressCheck.Invalid -> return Probe.Invalid(check.message)
            is AddressCheck.Ok -> check.url
        }
        val tried = candidates(url)
        store.hosts.value.firstOrNull { h -> tried.any { it.equals(h.url, ignoreCase = true) } }
            ?.let { return Probe.Invalid("Already added as ${it.label}") }
        if (isTailnetAddress(url)) tailscale().takeIf { it != Tailscale.ON }?.let { return Probe.NeedsTailscale(url, it) }
        return when (val result = firstToAnswer(candidates(url))) {
            is Probe.Connected -> result.copy(host = store.add(label.trim(), result.host.url))
            else -> result
        }
    }

    /**
     * Each URL tried at once. One that connects wins, though an earlier one, such as the address as
     * typed, still has [PREFER_EARLIER_MS] to connect too. When none does, the most telling failure:
     * a port that didn't answer says less than a server that did.
     */
    private suspend fun firstToAnswer(urls: List<String>): Probe = coroutineScope {
        if (urls.size == 1) return@coroutineScope test(urls[0])
        val results = Channel<Pair<Int, Probe>>(urls.size)
        urls.forEachIndexed { i, u -> launch { results.send(i to test(u)) } }
        val probes = arrayOfNulls<Probe>(urls.size)
        var best: Int? = null
        for (n in urls.indices) {
            val (i, probe) = (if (best == null) results.receive() else withTimeoutOrNull(PREFER_EARLIER_MS) { results.receive() })
                ?: break
            probes[i] = probe
            if (probe is Probe.Connected && (best == null || i < best)) best = i
            // Nothing earlier is still being tried.
            if (best != null && (0 until best).all { probes[it] != null }) break
        }
        coroutineContext.cancelChildren()
        best?.let { probes[it]!! }
            ?: probes.firstOrNull { (it as? Probe.Failed)?.problem != Problem.NO_ANSWER } ?: probes[0]!!
    }

    fun addAnyway(address: String, label: String): HostConfig? =
        (checkAddress(address) as? AddressCheck.Ok)?.let { store.add(label.trim(), it.url) }

    companion object {
        fun candidates(url: String): List<String> = ControlConnection.urlsToTry(url)

        const val PREFER_EARLIER_MS = 1_500L
    }
}
