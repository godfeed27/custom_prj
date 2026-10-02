package dev.walkdac.receiver

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * JSON-lines control channel to the sender (TCP 7701).
 * Incoming messages are delivered on the reader thread via [listener].
 */
class ControlClient(
    private val host: String,
    private val port: Int,
    private val listener: (JSONObject) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val writeLock = Any()
    @Volatile var connected = false
        private set

    fun connect(timeoutMs: Int = 4000) {
        val s = Socket()
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        connected = true
        Thread({ readLoop(s) }, "walkdac-control").apply { isDaemon = true; start() }
    }

    private fun readLoop(s: Socket) {
        var reason = "closed by sender"
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    listener(JSONObject(line))
                } catch (e: Exception) {
                    Log.w(TAG, "bad control message: ${e.message}")
                }
            }
        } catch (e: Exception) {
            reason = e.message ?: e.toString()
        } finally {
            connected = false
            close()
            onClosed(reason)
        }
    }

    /** Sends one message. Returns false if the connection is gone. */
    fun send(msg: JSONObject): Boolean {
        val w = writer ?: return false
        return try {
            synchronized(writeLock) {
                w.write(msg.toString())
                w.write("\n")
                w.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        connected = false
        try { socket?.close() } catch (e: Exception) { /* ignore */ }
        socket = null
        writer = null
    }

    companion object { private const val TAG = "WalkDAC.Control" }
}
