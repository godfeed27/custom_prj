package dev.walkdac.receiver

import android.graphics.Bitmap

/** What the sender told us about itself in the `source` message. */
data class SourceInfo(
    val name: String = "",
    val device: String = "",
    val capture: String = "",
    val rate: Int = 0,
    val codec: String = "",
    val bits: Int = 0,
    val channels: Int = 2,
    val blockMs: Int = 0,
    val mediaControl: Boolean = false,
)

/** Now-playing state as last reported by the sender (media-control on the Mac). */
data class NowPlayingInfo(
    val app: String = "",
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationUs: Long = -1,
    val elapsedUs: Long = -1,
    val rate: Double = 0.0,
    /** Sender clock (ns) at which elapsedUs was valid. */
    val atMacNs: Long = 0,
)

/** Everything the screen shows, sampled by the service on request. */
data class StatsSnapshot(
    val status: String,
    val host: String?,
    val reconnects: Int,
    val senders: List<Beacon>,
    val source: SourceInfo?,
    val nowPlaying: NowPlayingInfo,
    val positionUs: Long,
    val artwork: Bitmap?,
    // pipeline
    val engineRate: Int,
    val pipelineState: String,
    val fillMs: Double,
    val targetMs: Double,
    val driftPpm: Double,
    val underruns: Long,
    val hardResyncs: Long,
    val insertedFrames: Long,
    val droppedFrames: Long,
    val bufferOverflowFrames: Long,
    // stream
    val bitrateKbps: Double,
    val blocksReceived: Long,
    val lostBlocks: Long,
    val jitterMs: Double,
    val networkMs: Double,
    val rttMs: Double,
    val clockOffsetMs: Double,
    // output
    val outputLatencyMs: Double,
    val trackUnderruns: Int,
    val routedDevice: String,
    val mixerRate: Int,
    val mixerFramesPerBuffer: Int,
    val audioError: String?,
    // device
    val wifiRssi: Int,
    val wifiLinkMbps: Int,
    val wifiFreqMhz: Int,
    val batteryPercent: Int,
    val batteryTempC: Double,
    val batteryVolts: Double,
    val batteryCurrentMa: Int,
    val charging: Boolean,
    val lastCommand: String,
)
