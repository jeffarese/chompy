package com.jeffarese.rexmobile.rex

import com.jeffarese.rexmobile.terminal.RemoteTerminal
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TerminalStreamParserTest {
    private val nonce = 17145071078282362047uL

    private class Collect : StreamSink {
        val out = ByteArrayOutputStream()
        val msgs = mutableListOf<JsonObject>()
        override fun output(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
        override fun message(msg: JsonObject) {
            msgs += msg
        }
        val text get() = out.toString(Charsets.UTF_8.name())
        val types get() = msgs.map { it.str("type") }
    }

    private fun rex(n: ULong, json: String) = "\u001b_rex1;$n;$json\u001b\\"
    private val snapshot = "GHOSTSNP\u0001\u0000\u001b\u001b_garbage\u0000"

    @Test
    fun skipsSnapshotThenDeliversMessagesAndOutput() {
        val c = Collect()
        val p = TerminalStreamParser(nonce, c)
        p.push((snapshot + rex(nonce, """{"type":"resize","cols":80,"rows":24}""") + "hello").toByteArray())
        assertTrue(p.pastSnapshot)
        assertEquals(listOf("resize"), c.types)
        assertEquals("hello", c.text)
    }

    @Test
    fun emitsNothingInsideSnapshot() {
        val c = Collect()
        val p = TerminalStreamParser(nonce, c)
        p.push(snapshot.toByteArray())
        assertFalse(p.pastSnapshot)
        assertEquals(0, c.out.size())
    }

    @Test
    fun passesThroughForeignNonce() {
        val c = Collect()
        val p = TerminalStreamParser(nonce, c)
        val fake = rex(123uL, """{"type":"resize","cols":1,"rows":1}""")
        p.push((rex(nonce, """{"type":"resize","cols":80,"rows":24}""") + "a" + fake + "b").toByteArray())
        assertEquals(1, c.msgs.size)
        assertEquals("a${fake}b", c.text)
    }

    @Test
    fun handlesEverySplitPoint() {
        val whole = (snapshot + rex(nonce, """{"type":"resize","cols":80,"rows":24}""") + "one\u001b[31m" +
            rex(nonce, """{"type":"theme","foreground":"#ffffff"}""") + "two é").toByteArray()
        for (cut in 1 until whole.size) {
            val c = Collect()
            val p = TerminalStreamParser(nonce, c)
            p.push(whole.copyOfRange(0, cut))
            p.push(whole.copyOfRange(cut, whole.size))
            assertEquals("cut $cut", listOf("resize", "theme"), c.types)
            assertEquals("cut $cut", "one\u001b[31mtwo é", c.text)
        }
    }

    @Test
    fun handshakeEncoding() {
        val hello = DataHandshake.hello("AAECAwQFBgcICQoLDA0ODw")
        assertEquals(17, hello.size)
        assertEquals(1, hello[0].toInt())
        assertArrayEquals(ByteArray(16) { it.toByte() }, hello.copyOfRange(1, 17))

        val resume = DataHandshake.resume(ByteArray(16) { 7 }, 258)
        assertEquals(25, resume.size)
        assertEquals(2, resume[17].toInt())
        assertEquals(1, resume[18].toInt())

        val w = ByteArray(32)
        w[0] = 0x81.toByte()
        java.nio.ByteBuffer.wrap(w).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(20, 60000).putLong(24, nonce.toLong())
        val reply = DataHandshake.decode(w) as DataHandshake.Welcome
        assertEquals(60000L, reply.keepMs)
        assertEquals(nonce, reply.nonce)
        assertEquals(DataHandshake.Reject(3), DataHandshake.decode(byteArrayOf(0x82.toByte(), 3)))
    }

    @Test
    fun utf8CompletePrefix() {
        val e = "é".toByteArray() // 2 bytes
        assertEquals(1, RemoteTerminal.completeUtf8Length(byteArrayOf('a'.code.toByte(), e[0])))
        assertEquals(3, RemoteTerminal.completeUtf8Length(byteArrayOf('a'.code.toByte()) + e))
        val emoji = "😀".toByteArray() // 4 bytes
        assertEquals(0, RemoteTerminal.completeUtf8Length(emoji.copyOfRange(0, 3)))
        assertEquals(4, RemoteTerminal.completeUtf8Length(emoji))
    }

    @Test
    fun normalizesHostAddresses() {
        assertEquals("https://studio-rex.tail1.ts.net", ControlConnection.normalizeHostUrl("studio-rex.tail1.ts.net"))
        assertEquals("https://studio-rex.tail1.ts.net", ControlConnection.normalizeHostUrl(" https://studio-rex.tail1.ts.net/ "))
        assertEquals("http://box:7297", ControlConnection.normalizeHostUrl("ws://box:7297"))
    }
}
