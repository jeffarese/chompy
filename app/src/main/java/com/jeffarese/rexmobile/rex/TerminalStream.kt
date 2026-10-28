package com.jeffarese.rexmobile.rex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

private const val ESC: Byte = 0x1b
/** An unterminated message longer than this is treated as program output. */
private const val MAX_MESSAGE_BYTES = 1 shl 20

interface StreamSink {
    /** Bytes of the snapshot the stream starts with. */
    fun snapshot(bytes: ByteArray, offset: Int, length: Int) = Unit
    fun output(bytes: ByteArray, offset: Int, length: Int)
    fun message(msg: JsonObject)
}

/**
 * Splits a Rex terminal stream into its snapshot, program output and Rex's own messages.
 *
 * The stream starts with a snapshot of the terminal in Ghostty's binary snapshot format. The first
 * Rex message, a `resize`, comes right after it, so everything before that message is the
 * snapshot. Rex messages are APC sequences `ESC _ rex1;<nonce>;<json> ESC \`; one with another
 * nonce was printed by a program and passes through untouched.
 */
class TerminalStreamParser(nonce: ULong, private val sink: StreamSink) {
    private val marker = "\u001b_rex1;$nonce;".toByteArray()
    private var carry = ByteArray(0)

    var pastSnapshot = false
        private set

    fun push(chunk: ByteArray) {
        val data = if (carry.isNotEmpty()) carry + chunk else chunk
        carry = ByteArray(0)
        var start = 0
        var i = 0
        while (i < data.size) {
            val esc = indexOf(data, ESC, i)
            if (esc < 0) break
            when (matchMarker(data, esc)) {
                Match.PARTIAL -> {
                    emit(data, start, esc)
                    carry = data.copyOfRange(esc, data.size)
                    return
                }
                Match.NO -> {
                    i = esc + 1
                    continue
                }
                Match.YES -> Unit
            }
            val bodyStart = esc + marker.size
            val end = findSt(data, bodyStart)
            if (end < 0) {
                if (data.size - esc > MAX_MESSAGE_BYTES) {
                    i = esc + 1
                    continue
                }
                emit(data, start, esc)
                carry = data.copyOfRange(esc, data.size)
                return
            }
            emit(data, start, esc)
            pastSnapshot = true
            deliver(data, bodyStart, end)
            i = end + 2
            start = i
        }
        emit(data, start, data.size)
    }

    private fun emit(data: ByteArray, from: Int, to: Int) {
        if (to <= from) return
        if (pastSnapshot) sink.output(data, from, to - from) else sink.snapshot(data, from, to - from)
    }

    private fun deliver(data: ByteArray, from: Int, to: Int) {
        val obj = runCatching {
            RexJson.parseToJsonElement(String(data, from, to - from, Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: return
        if (obj.str("type") != null) sink.message(obj)
    }

    private enum class Match { YES, NO, PARTIAL }

    private fun matchMarker(data: ByteArray, at: Int): Match {
        for (k in marker.indices) {
            if (at + k >= data.size) return Match.PARTIAL
            if (data[at + k] != marker[k]) return Match.NO
        }
        return Match.YES
    }

    private fun findSt(data: ByteArray, from: Int): Int {
        var i = indexOf(data, ESC, from)
        while (i >= 0 && i + 1 < data.size) {
            if (data[i + 1] == 0x5c.toByte()) return i
            i = indexOf(data, ESC, i + 1)
        }
        return -1
    }

    private fun indexOf(data: ByteArray, b: Byte, from: Int): Int {
        for (i in from until data.size) if (data[i] == b) return i
        return -1
    }
}

// Data connection handshake messages. Integers are unsigned little-endian.

object DataHandshake {
    fun hello(ticket: String): ByteArray {
        val t = Base64.getUrlDecoder().decode(ticket)
        require(t.size == 16) { "ticket must be 16 bytes" }
        return byteArrayOf(0x01) + t
    }

    fun resume(token: ByteArray, received: Long): ByteArray =
        ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x02).put(token).putLong(received).array()

    sealed interface Reply
    data class Welcome(val token: ByteArray, val keepMs: Long, val nonce: ULong) : Reply
    data class Reject(val reason: Int) : Reply

    fun decode(msg: ByteArray): Reply? {
        if (msg.size == 32 && msg[0] == 0x81.toByte()) {
            val buf = ByteBuffer.wrap(msg).order(ByteOrder.LITTLE_ENDIAN)
            return Welcome(
                token = msg.copyOfRange(1, 17),
                keepMs = buf.getInt(20).toLong() and 0xffffffffL,
                nonce = buf.getLong(24).toULong(),
            )
        }
        if (msg.size >= 2 && msg[0] == 0x82.toByte()) return Reject(msg[1].toInt() and 0xff)
        return null
    }

    val rejectReasons = mapOf(
        1 to "ticket unknown or expired",
        2 to "already streaming this block",
        3 to "stream unavailable",
        4 to "limit reached",
        5 to "malformed handshake",
        6 to "server failed",
        7 to "wrong subprotocol",
    )
}
