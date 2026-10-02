package dev.walkdac.receiver

import android.os.SystemClock
import android.util.Log
import dev.walkdac.core.Protocol
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** A sender announced by a UDP beacon. */
data class Beacon(
    val host: String,
    val name: String,
    val audioPort: Int,
    val controlPort: Int,
    val rate: Int,
    val codec: String,
    val lastSeenMs: Long,
)

/** Listens for walkdac_sender.py beacons (JSON over UDP broadcast, port 7702). */
class Discovery(private val port: Int = Protocol.DEFAULT_BEACON_PORT) {
    private val beacons = HashMap<String, Beacon>()
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "walkdac-discovery").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        socket?.close()
        socket = null
    }

    private fun loop() {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 2000
                    bind(InetSocketAddress(port))
                }
                socket = s
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(pkt)
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    }
                    parse(String(pkt.data, 0, pkt.length, Charsets.UTF_8), pkt.address.hostAddress ?: continue)
                }
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "discovery socket error: $e")
                    SystemClock.sleep(2000)
                }
            } finally {
                socket?.close()
                socket = null
            }
        }
    }

    private fun parse(text: String, host: String) {
        try {
            val j = JSONObject(text)
            if (j.optString("t") != "beacon") return
            val b = Beacon(
                host = host,
                name = j.optString("name", host),
                audioPort = j.optInt("audio_port", Protocol.DEFAULT_AUDIO_PORT),
                controlPort = j.optInt("control_port", Protocol.DEFAULT_CONTROL_PORT),
                rate = j.optInt("rate", 0),
                codec = j.optString("codec", ""),
                lastSeenMs = SystemClock.elapsedRealtime(),
            )
            synchronized(beacons) { beacons[host] = b }
        } catch (e: Exception) {
            // not ours
        }
    }

    /** Senders seen in the last 10 s, most recent first. */
    fun senders(): List<Beacon> {
        val now = SystemClock.elapsedRealtime()
        synchronized(beacons) {
            beacons.values.removeAll { now - it.lastSeenMs > 10_000 }
            return beacons.values.sortedByDescending { it.lastSeenMs }
        }
    }

    companion object { private const val TAG = "WalkDAC.Discovery" }
}
