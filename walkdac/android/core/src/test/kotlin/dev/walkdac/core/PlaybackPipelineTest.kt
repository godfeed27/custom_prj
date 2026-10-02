package dev.walkdac.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Simulates a sender whose clock differs from the receiver's by a few hundred ppm. */
class PlaybackPipelineTest {
    private val rate = 48000
    private val ch = 2
    private val block = 480 // 10 ms

    private class SineGen(val rate: Int) {
        var pos = 0L
        fun block(frames: Int, ch: Int): FloatArray {
            val out = FloatArray(frames * ch)
            for (i in 0 until frames) {
                val v = (0.5 * sin(2 * PI * 1000.0 * (pos + i) / rate)).toFloat()
                for (c in 0 until ch) out[i * ch + c] = v
            }
            pos += frames
            return out
        }
    }

    /**
     * Runs [seconds] of simulated time. The producer pushes one block every 10 ms of sender time;
     * the consumer pulls one block every 10 ms of receiver time, which runs (1 + ppmError/1e6) times faster.
     */
    private fun simulate(ppmError: Double, seconds: Int, targetMs: Double = 450.0, gapAt: Pair<Double, Double>? = null): Pair<PlaybackPipeline, Stats> {
        val p = PlaybackPipeline(rate, ch, block, targetMs)
        val gen = SineGen(rate)
        val out = FloatArray(block * ch)
        val blockSec = block.toDouble() / rate
        var producerTime = 0.0
        var produced = 0L
        val fills = ArrayList<Double>()
        var maxStep = 0f
        var last = Float.NaN
        var firstAudioBlock = -1L
        val consumerBlocks = (seconds / blockSec).toLong()
        for (j in 0 until consumerBlocks) {
            val tc = j * blockSec / (1.0 + ppmError / 1e6)
            while (producerTime <= tc) {
                val inGap = gapAt != null && producerTime >= gapAt.first && producerTime < gapAt.second
                val b = gen.block(block, ch)
                if (!inGap) p.push(b, 0, b.size)
                produced++
                producerTime += blockSec
            }
            val audio = p.nextBlock(out)
            if (audio && firstAudioBlock < 0) firstAudioBlock = j
            if (audio) {
                for (i in 0 until block) {
                    val v = out[i * ch]
                    if (!last.isNaN()) maxStep = maxOf(maxStep, abs(v - last))
                    last = v
                }
            } else {
                last = Float.NaN
            }
            if (tc > 60.0) fills.add(p.fillMs)
        }
        return p to Stats(fills, maxStep, firstAudioBlock)
    }

    private class Stats(val fillsAfter60s: List<Double>, val maxStep: Float, val firstAudioBlock: Long)

    private val smoothStep = (2 * PI * 1000.0 / 48000.0 * 0.5 * 1.06).toFloat()

    @Test fun prebuffersToTargetThenPlays() {
        val (p, s) = simulate(0.0, 5)
        // target 450 ms = 45 blocks of silence before audio starts (±1)
        assertTrue("first audio block ${s.firstAudioBlock}", s.firstAudioBlock in 44L..46L)
        assertEquals(0, p.underruns)
        assertEquals(0, p.hardResyncs)
        assertTrue("max step ${s.maxStep}", s.maxStep < smoothStep)
        assertEquals(PlaybackPipeline.State.PLAYING, p.state)
    }

    @Test fun compensatesFastReceiverClock() {
        val (p, s) = simulate(+150.0, 600) // receiver consumes 150 ppm too fast -> must insert frames
        assertEquals(0, p.underruns)
        assertEquals(0, p.hardResyncs)
        val worst = s.fillsAfter60s.maxOf { abs(it - 450.0) }
        assertTrue("fill stayed within ±60 ms of target, worst $worst", worst < 60.0)
        val expectedInserted = 600.0 * rate * 150e-6
        assertTrue("inserted ${p.insertedFrames} vs ~${expectedInserted}", abs(p.insertedFrames - expectedInserted) < expectedInserted * 0.35)
        assertTrue("max step ${s.maxStep}", s.maxStep < smoothStep)
    }

    @Test fun compensatesSlowReceiverClock() {
        val (p, s) = simulate(-150.0, 600) // receiver too slow -> buffer grows -> must drop frames
        assertEquals(0, p.underruns)
        assertEquals(0, p.hardResyncs)
        val worst = s.fillsAfter60s.maxOf { abs(it - 450.0) }
        assertTrue("worst deviation $worst", worst < 60.0)
        assertTrue(p.droppedFrames > 0)
        assertTrue("max step ${s.maxStep}", s.maxStep < smoothStep)
    }

    @Test fun recoversFromNetworkGapWithOneUnderrun() {
        // 1.2 s with no data at t=10 s: buffer (450 ms) drains -> underrun -> silence -> prebuffer -> resumes
        val (p, s) = simulate(0.0, 30, gapAt = 10.0 to 11.2)
        assertEquals(1, p.underruns)
        assertEquals(PlaybackPipeline.State.PLAYING, p.state)
        assertTrue(s.fillsAfter60s.isEmpty()) // only 30 s simulated
        assertTrue(p.silentBlocks in 100L..300L) // ~45 prebuffer + ~120 gap + 45 re-prebuffer
    }

    @Test fun hardResyncWhenBufferRunsAway() {
        val p = PlaybackPipeline(rate, ch, block, 450.0)
        val gen = SineGen(rate)
        val out = FloatArray(block * ch)
        repeat(150) { val b = gen.block(block, ch); p.push(b, 0, b.size) } // 1.5 s queued at once
        assertTrue(p.nextBlock(out))
        assertEquals(1, p.hardResyncs)
        assertTrue("fill ${p.fillMs}", abs(p.fillMs - 450.0) < 15.0)
        p.flush()
        assertFalse(p.nextBlock(out))
        assertEquals(PlaybackPipeline.State.PREBUFFER, p.state)
        assertEquals(0, p.buffer.availableFrames)
    }
}
