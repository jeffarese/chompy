package com.jeffarese.rexmobile.rex

import com.jeffarese.rexmobile.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RexCallException(val method: String, val error: RexErrorBody) :
    Exception("$method: ${error.message}") {
    val code get() = error.code
}

class ConnectionClosedException(message: String) : Exception(message)

data class Welcome(val clientId: String?, val reconnectToken: String?, val resumed: Boolean)

interface ControlListener {
    fun onWelcome(welcome: Welcome)
    fun onEvent(seq: Long, event: JsonObject)
    fun onClosed(code: Int, reason: String)
}

/**
 * One control WebSocket. It doesn't reconnect by itself: the owner opens a new one with the
 * reconnect token from the last welcome, so the server continues the same client.
 */
class ControlConnection(
    client: OkHttpClient,
    val baseUrl: String,
    private val listener: ControlListener,
    reconnectToken: String?,
) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, Pending>()
    @Volatile private var closed = false
    @Volatile var welcome: Welcome? = null
        private set

    private class Pending(val method: String, val result: CompletableDeferred<JsonElement>)

    private val ws: WebSocket = client.newWebSocket(
        Request.Builder()
            .url("${toWs(baseUrl)}/api/control")
            .header("Sec-WebSocket-Protocol", CONTROL_PROTOCOL)
            .build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = buildJsonObject {
                    put("type", "hello")
                    put("api_revision", API_REVISION)
                    put("api_min_revision", API_REVISION)
                    putJsonObject("client") {
                        put("kind", "app")
                        put("platform", "other")
                        put("name", "Chompy")
                        put("version", BuildConfig.VERSION_NAME)
                    }
                    if (reconnectToken != null) put("reconnect_token", reconnectToken)
                }
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = receive(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(code, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                finish(1006, t.message ?: "connection failed")
        },
    )

    val isOpen get() = !closed && welcome != null

    /** How many messages have arrived, so a caller can tell the connection is still receiving. */
    @Volatile var messagesReceived = 0L
        private set

    /** Calls still waiting for their answer. */
    val callsPending: Int get() = pending.size

    suspend fun call(method: String, payload: JsonObject? = null, timeoutMs: Long = 15_000): JsonElement {
        if (closed) throw ConnectionClosedException("not connected")
        val id = nextId.getAndIncrement().toString()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = Pending(method, deferred)
        val msg = buildJsonObject {
            put("type", "request")
            put("id", id)
            put("method", method)
            if (payload != null) put("payload", payload)
        }
        if (!ws.send(msg.toString())) {
            pending.remove(id)
            throw ConnectionClosedException("send failed")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    suspend inline fun <reified T> callAs(method: String, payload: JsonObject? = null, timeoutMs: Long = 15_000): T =
        RexJson.decodeFromJsonElement(call(method, payload, timeoutMs))

    /** Leave for good: a close frame ends the client on the server at once. */
    fun close() {
        if (closed) return
        ws.close(1000, null)
        finish(1000, "closed by client")
    }

    /** Give up on a socket that looks dead. The server keeps the client for a resume. */
    fun abandon() {
        if (closed) return
        ws.cancel()
        finish(1006, "abandoned")
    }

    private fun receive(text: String) {
        messagesReceived++
        // Fields read leniently: a value of an unexpected type is a missing one, rather than an error that drops the connection.
        val msg = runCatching { RexJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (msg.str("type")) {
            "welcome" -> {
                if (msg.str("protocol") != CONTROL_PROTOCOL) {
                    ws.close(1002, "unexpected protocol")
                    return
                }
                val c = msg["client"] as? JsonObject
                val w = Welcome(
                    clientId = c?.str("id"),
                    reconnectToken = c?.str("reconnect_token"),
                    resumed = (c?.get("resumed") as? JsonPrimitive)?.booleanOrNull ?: false,
                )
                welcome = w
                listener.onWelcome(w)
            }
            "response" -> {
                val id = msg.str("id") ?: return
                val p = pending.remove(id) ?: return
                val err = msg["error"] as? JsonObject
                if (err != null) {
                    // An error body of an unexpected shape is still the call's error, not the connection's.
                    val body = runCatching { RexJson.decodeFromJsonElement<RexErrorBody>(err) }.getOrElse { RexErrorBody(message = err.toString()) }
                    p.result.completeExceptionally(RexCallException(p.method, body))
                } else {
                    p.result.complete(msg["payload"] ?: JsonObject(emptyMap()))
                }
            }
            "event" -> {
                val seq = msg.long("seq") ?: 0
                val ev = msg["payload"] as? JsonObject ?: return
                listener.onEvent(seq, ev)
            }
        }
    }

    private fun finish(code: Int, reason: String) {
        if (closed) return
        closed = true
        val err = ConnectionClosedException(reason.ifBlank { "connection closed ($code)" })
        pending.values.forEach { it.result.completeExceptionally(err) }
        pending.clear()
        listener.onClosed(code, reason)
    }

    companion object {
        fun toWs(baseUrl: String) =
            baseUrl.replaceFirst(Regex("^https:", RegexOption.IGNORE_CASE), "wss:")
                .replaceFirst(Regex("^http:", RegexOption.IGNORE_CASE), "ws:")

        /** Turn a host address into an https:// base URL. Accepts bare names, host:port and URLs. */
        fun normalizeHostUrl(input: String): String {
            var s = input.trim().trimEnd('/')
            if (!Regex("^[a-z]+://", RegexOption.IGNORE_CASE).containsMatchIn(s)) s = "https://$s"
            s = s.replaceFirst(Regex("^wss:", RegexOption.IGNORE_CASE), "https:")
                .replaceFirst(Regex("^ws:", RegexOption.IGNORE_CASE), "http:")
            val uri = java.net.URI(s)
            // URI leaves the host out, rather than failing, for names such as my_mac.ts.net.
            val host = uri.host ?: throw IllegalArgumentException("not a host name: $input")
            val port = if (uri.port > 0) ":${uri.port}" else ""
            return "${uri.scheme}://$host$port"
        }

        /**
         * The URLs to try for a normalized [url]: an https one without a port is tried on 7297 too,
         * where Tailscale Serve publishes Rex on Linux servers, as Rex's own apps do.
         */
        fun urlsToTry(url: String): List<String> =
            if (url.startsWith("https://", ignoreCase = true) && ':' !in url.substringAfter("://").substringAfterLast(']')) {
                listOf(url, "$url:7297")
            } else listOf(url)
    }
}

// Small helpers for reading loosely typed event payloads.
fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.long(key: String): Long? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
fun JsonObject.int(key: String): Int? = long(key)?.toInt()
