package com.jeffarese.rexmobile.rex

import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/** Sockets that read [bytesPerSecond] at most and keep a small receive buffer, like a phone on a slow network. */
class SlowLink(private val bytesPerSecond: Int) : SocketFactory() {
    private fun slow() = object : Socket() {
        init {
            receiveBufferSize = 64 * 1024
        }

        private val input by lazy {
            val inner = super.getInputStream()
            object : InputStream() {
                override fun read(): Int = inner.read().also { if (it >= 0) Thread.sleep(1000L / bytesPerSecond) }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    inner.read(b, off, minOf(len, 1024)).also { if (it > 0) Thread.sleep(it * 1000L / bytesPerSecond) }
                override fun available() = inner.available()
                override fun close() = inner.close()
            }
        }

        override fun getInputStream(): InputStream = input
    }

    override fun createSocket(): Socket = slow()
    override fun createSocket(host: String?, port: Int): Socket = slow().apply { connect(java.net.InetSocketAddress(host, port)) }
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)
    override fun createSocket(host: InetAddress?, port: Int): Socket = slow().apply { connect(java.net.InetSocketAddress(host, port)) }
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket(address, port)
}
