package dev.walkdac.receiver

import dev.walkdac.core.FrameHeader
import dev.walkdac.core.Pcm
import dev.walkdac.core.Protocol
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Reads audio frames from the sender (TCP 7700) and hands decoded float samples to [onFrame].
 * Runs on its own thread until the socket fails or [close] is called.
 */
class AudioClient(
    private val host: String,
    private val port: Int,
    /** header, interleaved float samples, sample count, arrival time (System.nanoTime) */
    private val onFrame: (FrameHeader, FloatArray, Int, Long) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    private var socket: Socket? = null
    @Volatile private var closed = false

    fun connect(timeoutMs: Int = 4000) {
        val s = Socket()
        s.tcpNoDelay = true
        s.keepAlive = true
        s.soTimeout = 8000 // no audio for 8 s = dead link
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        Thread({ readLoop(s) }, "walkdac-audio-net").apply { isDaemon = true; start() }
    }

    private fun readLoop(s: Socket) {
        var reason = "closed by sender"
        try {
            val input = DataInputStream(s.getInputStream().buffered(256 * 1024))
            val header = ByteArray(Protocol.HEADER_SIZE)
            var payload = ByteArray(64 * 1024)
            var floats = FloatArray(32 * 1024)
            while (!closed) {
                input.readFully(header)
                val h = Protocol.parseHeader(header)
                if (payload.size < h.payloadLen) payload = ByteArray(h.payloadLen)
                input.readFully(payload, 0, h.payloadLen)
                val arrival = System.nanoTime()
                val samples = h.frames * h.channels
                if (floats.size < samples) floats = FloatArray(samples)
                val n = Pcm.decode(h.codec, payload, h.payloadLen, floats)
                onFrame(h, floats, n, arrival)
            }
        } catch (e: Exception) {
            reason = if (closed) "closed" else (e.message ?: e.toString())
        } finally {
            close()
            onClosed(reason)
        }
    }

    fun close() {
        closed = true
        try { socket?.close() } catch (e: Exception) { /* ignore */ }
        socket = null
    }
}
