package dev.walkdac.core

import kotlin.math.abs

/**
 * PI controller that keeps the jitter buffer near its target by nudging the playback rate.
 *
 * Output is in ppm: positive = consume faster (drop frames, buffer too full),
 * negative = consume slower (insert frames, buffer too empty). Clamped to ±[maxPpm].
 * The fill level is smoothed with a ~1 s time constant so network jitter does not steer the rate.
 */
class DriftController(
    var targetMs: Double,
    val kp: Double = 4.0, // ppm per ms of error
    val ki: Double = 0.05, // ppm per ms·s of accumulated error
    val maxPpm: Double = 500.0,
    val smoothingSec: Double = 1.0,
) {
    var ppm: Double = 0.0
        private set
    var smoothedFillMs: Double = Double.NaN
        private set
    private var integral = 0.0

    fun reset() {
        ppm = 0.0
        smoothedFillMs = Double.NaN
        integral = 0.0
    }

    fun update(fillMs: Double, dtSec: Double): Double {
        if (smoothedFillMs.isNaN()) {
            smoothedFillMs = fillMs
        } else {
            val alpha = (dtSec / smoothingSec).coerceIn(0.0, 1.0)
            smoothedFillMs += (fillMs - smoothedFillMs) * alpha
        }
        val err = smoothedFillMs - targetMs
        var out = kp * err + ki * integral
        val saturated = abs(out) >= maxPpm
        if (!saturated || (err * out < 0)) {
            integral += err * dtSec
        }
        out = kp * err + ki * integral
        ppm = out.coerceIn(-maxPpm, maxPpm)
        return ppm
    }
}

/**
 * Applies a ppm rate change by dropping or inserting single frames spread evenly over a block
 * (the Snapcast approach). Dropped frames are averaged into their predecessor and inserted
 * frames are the average of their neighbours, so each correction is a tiny, inaudible step.
 */
class RateAdjuster(val channels: Int) {
    private var acc = 0.0

    /**
     * How many extra input frames to consume for an output block of [outFrames] at [ppm].
     * Positive = drop that many frames (consume more), negative = insert (consume fewer).
     */
    fun delta(outFrames: Int, ppm: Double): Int {
        acc += outFrames * ppm / 1e6
        var d = 0
        while (acc >= 1.0) { d++; acc -= 1.0 }
        while (acc <= -1.0) { d--; acc += 1.0 }
        return d
    }

    fun reset() { acc = 0.0 }

    /**
     * Converts [inFrames] frames of [input] into exactly [outFrames] frames in [out].
     * Requires inFrames == outFrames + delta with |delta| small compared to outFrames.
     *
     * Each one-frame correction is spread over [RAMP_FRAMES] output frames as a slowly moving
     * fractional read position with linear interpolation, so the worst-case step between two
     * output samples grows by only 1/RAMP_FRAMES instead of the 50% jump a plain drop causes.
     */
    fun process(input: FloatArray, inFrames: Int, out: FloatArray, outFrames: Int) {
        val ch = channels
        if (inFrames <= 0) {
            java.util.Arrays.fill(out, 0, outFrames * ch, 0f)
            return
        }
        val delta = inFrames - outFrames
        if (delta == 0) {
            System.arraycopy(input, 0, out, 0, outFrames * ch)
            return
        }
        val n = kotlin.math.abs(delta)
        val sign = if (delta > 0) 1.0 else -1.0
        val spacing = outFrames.toDouble() / n
        val ramp = minOf(RAMP_FRAMES.toDouble(), spacing)
        val lastIn = inFrames - 1
        for (o in 0 until outFrames) {
            var shift = 0.0
            for (j in 0 until n) {
                val start = spacing * j + spacing / 2.0 - ramp / 2.0
                val s = (o - start) / ramp
                shift += if (s <= 0.0) 0.0 else if (s >= 1.0) 1.0 else s
            }
            val p = o + sign * shift
            var i0 = kotlin.math.floor(p).toInt()
            var frac = (p - i0).toFloat()
            if (i0 < 0) { i0 = 0; frac = 0f }
            if (i0 >= lastIn) { i0 = lastIn; frac = 0f }
            val i1 = if (i0 < lastIn) i0 + 1 else i0
            val a = i0 * ch
            val b = i1 * ch
            val oc = o * ch
            for (c in 0 until ch) {
                out[oc + c] = input[a + c] * (1f - frac) + input[b + c] * frac
            }
        }
    }

    companion object {
        /** Output frames over which one inserted/dropped frame is smeared. */
        const val RAMP_FRAMES = 32
    }
}
