package dev.walkdac.core

import kotlin.math.abs

/** Counters for the incoming audio stream: throughput, sequence gaps and RFC 3550 style inter-arrival jitter. */
class StreamStats {
    var framesReceived: Long = 0
        private set
    var blocksReceived: Long = 0
        private set
    var bytesReceived: Long = 0
        private set
    /** Blocks missing from the sequence (the sender dropped them when we fell behind). */
    var lostBlocks: Long = 0
        private set
    var lastSeq: Long = -1
        private set
    /** Smoothed inter-arrival jitter in ms (RFC 3550, gain 1/16). */
    var jitterMs: Double = 0.0
        private set
    var lastArrivalNs: Long = 0
        private set
    var lastCaptureNs: Long = 0
        private set

    private var windowStartNs = 0L
    private var windowBytes = 0L
    /** Bytes per second measured over the last ~1 s window. */
    var bitrateBps: Double = 0.0
        private set

    fun onBlock(seq: Long, frames: Int, bytes: Int, captureNs: Long, arrivalNs: Long) {
        blocksReceived++
        framesReceived += frames
        bytesReceived += bytes
        if (lastSeq >= 0) {
            val expected = (lastSeq + 1) and 0xFFFFFFFFL
            if (seq != expected) {
                val gap = ((seq - expected) and 0xFFFFFFFFL)
                if (gap in 1..100000) lostBlocks += gap
            }
            val d = (arrivalNs - lastArrivalNs) - (captureNs - lastCaptureNs)
            jitterMs += (abs(d) / 1e6 - jitterMs) / 16.0
        }
        lastSeq = seq
        lastArrivalNs = arrivalNs
        lastCaptureNs = captureNs
        if (windowStartNs == 0L) windowStartNs = arrivalNs
        windowBytes += bytes
        val span = arrivalNs - windowStartNs
        if (span >= 1_000_000_000L) {
            bitrateBps = windowBytes * 8.0 * 1e9 / span
            windowStartNs = arrivalNs
            windowBytes = 0
        }
    }

    fun reset() {
        framesReceived = 0; blocksReceived = 0; bytesReceived = 0; lostBlocks = 0; lastSeq = -1
        jitterMs = 0.0; lastArrivalNs = 0; lastCaptureNs = 0; windowStartNs = 0; windowBytes = 0; bitrateBps = 0.0
    }
}
