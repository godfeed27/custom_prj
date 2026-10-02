package dev.walkdac.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class BufferAndDriftTest {
    @Test fun jitterBufferWrapsAndDropsOldest() {
        val jb = JitterBuffer(10, 2) // 20 samples
        val src = FloatArray(16) { it.toFloat() }
        jb.write(src, 0, 16)
        assertEquals(8, jb.availableFrames)
        val dst = FloatArray(6)
        assertEquals(3, jb.read(dst, 0, 3))
        assertEquals(listOf(0f, 1f, 2f, 3f, 4f, 5f), dst.toList())
        jb.write(src, 0, 16) // 10 + 16 > 20: drops the 6 oldest samples (3 frames)
        assertEquals(10, jb.availableFrames)
        assertEquals(3, jb.overflowFrames.toInt())
        val all = FloatArray(20)
        assertEquals(10, jb.read(all, 0, 10))
        assertEquals(listOf(12f, 13f, 14f, 15f), all.take(4))
        assertEquals(listOf(0f, 1f), all.drop(4).take(2))
        assertEquals(0, jb.availableFrames)
        jb.write(src, 0, 4); assertEquals(2, jb.skip(5)); jb.write(src, 0, 2); jb.clear(); assertEquals(0, jb.availableFrames)
    }

    @Test fun rateAdjusterAccumulatesPpm() {
        val ra = RateAdjuster(2)
        var total = 0
        repeat(1000) { total += ra.delta(480, 500.0) }
        assertTrue("total=$total", abs(total - 240) <= 1) // 1000 blocks * 480 * 500e-6
        total = 0
        repeat(1000) { total += ra.delta(480, -55.0) }
        assertTrue("total=$total", abs(total + 26) <= 1)
    }

    private fun sine(frames: Int, ch: Int, start: Int): FloatArray =
        FloatArray(frames * ch) { i -> (0.5 * sin(2 * PI * 1000.0 * (start + i / ch) / 48000.0)).toFloat() }

    private fun maxStep(x: FloatArray, ch: Int): Float {
        var m = 0f
        for (i in ch until x.size) m = maxOf(m, abs(x[i] - x[i - ch]))
        return m
    }

    @Test fun rateAdjusterKeepsLengthAndContinuity() {
        val ra = RateAdjuster(2)
        val smoothStep = (2 * PI * 1000.0 / 48000.0 * 0.5 * 1.06).toFloat()
        for (delta in listOf(-3, -1, 0, 1, 3)) {
            val inFrames = 480 + delta
            val input = sine(inFrames, 2, 0)
            val out = FloatArray(480 * 2) { Float.NaN }
            ra.process(input, inFrames, out, 480)
            assertTrue(out.none { it.isNaN() })
            assertEquals(input[0], out[0], 1e-6f)
            assertTrue("delta=$delta step=${maxStep(out, 2)}", maxStep(out, 2) < smoothStep)
        }
    }

    @Test fun driftControllerSignsAndClamp() {
        val dc = DriftController(450.0)
        repeat(300) { dc.update(600.0, 0.01) } // 150 ms too full -> consume faster
        assertTrue(dc.ppm > 100.0)
        assertTrue(dc.ppm <= 500.0)
        dc.reset()
        repeat(300) { dc.update(300.0, 0.01) }
        assertTrue(dc.ppm < -100.0)
        assertTrue(dc.ppm >= -500.0)
        dc.reset()
        repeat(300) { dc.update(450.0, 0.01) }
        assertEquals(0.0, dc.ppm, 1e-9)
    }

    @Test fun timeSyncMedianOffset() {
        val ts = TimeSync(5)
        // remote clock = local + 1_000_000_000, symmetric 4 ms rtt, one outlier
        ts.add(0, 4_000_000, 1_000_000_000 + 2_000_000)
        ts.add(10_000_000, 14_000_000, 1_000_000_000 + 12_000_000)
        ts.add(20_000_000, 60_000_000, 1_000_000_000 + 22_000_000) // slow reply: offset looks 18 ms low
        assertEquals(1_000_000_000L, ts.offsetNs)
        assertEquals(4_000_000L, ts.minRttNs)
        assertEquals(1_000_000_000L + 123L, ts.remoteNow(123))
    }

    @Test fun streamStatsCountsGapsAndJitter() {
        val st = StreamStats()
        var t = 0L
        for (seq in 0L until 10L) {
            if (seq == 5L) continue // one lost block
            st.onBlock(seq, 480, 2880, captureNs = seq * 10_000_000, arrivalNs = seq * 10_000_000 + 3_000_000)
            t = seq
        }
        assertEquals(9, st.blocksReceived)
        assertEquals(1, st.lostBlocks)
        assertEquals(0.0, st.jitterMs, 1e-9) // perfectly regular arrival
        st.onBlock(10, 480, 2880, 100_000_000, 100_000_000 + 3_000_000 + 4_000_000)
        assertTrue(st.jitterMs > 0.2)
        val wrap = StreamStats()
        wrap.onBlock(0xFFFFFFFEL, 1, 1, 0, 0); wrap.onBlock(0xFFFFFFFFL, 1, 1, 1, 1); wrap.onBlock(0, 1, 1, 2, 2)
        assertEquals(0, wrap.lostBlocks) // 32-bit sequence wrap is not a gap
    }
}
