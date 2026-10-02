package dev.walkdac.core

/**
 * The receiver's playback engine without any Android dependency:
 * network thread calls [push], audio thread calls [nextBlock] once per output block.
 *
 * States: PREBUFFER (play silence until the buffer reaches the target), PLAYING.
 * - Buffer below [minPlayMs] → underrun, back to PREBUFFER.
 * - Buffer above target + [maxOverMs] → hard resync (skip ahead to the target).
 * - Otherwise a PI controller nudges the consume rate within ±500 ppm by dropping/inserting single frames.
 */
class PlaybackPipeline(
    val sampleRate: Int,
    val channels: Int,
    val blockFrames: Int,
    targetMs: Double,
    capacityMs: Double = 8000.0,
) {
    enum class State { PREBUFFER, PLAYING }

    val buffer = JitterBuffer(maxOf(blockFrames * 4, (sampleRate * capacityMs / 1000.0).toInt()), channels)
    val drift = DriftController(targetMs)
    private val adjuster = RateAdjuster(channels)
    private val maxDelta = 64
    private val scratch = FloatArray((blockFrames + maxDelta) * channels)

    val blockMs: Double = blockFrames * 1000.0 / sampleRate
    val minPlayMs: Double = maxOf(20.0, 2.0 * blockMs)
    var maxOverMs: Double = 600.0

    var targetMs: Double
        get() = drift.targetMs
        set(value) { drift.targetMs = value.coerceIn(minPlayMs + blockMs, 10000.0) }

    @Volatile var state: State = State.PREBUFFER
        private set
    @Volatile private var flushRequested = false

    var underruns: Long = 0; private set
    var hardResyncs: Long = 0; private set
    var droppedFrames: Long = 0; private set
    var insertedFrames: Long = 0; private set
    var blocksOut: Long = 0; private set
    var silentBlocks: Long = 0; private set

    val fillMs: Double get() = buffer.fillMs(sampleRate)
    val ppm: Double get() = drift.ppm

    /** Called from the network thread with decoded interleaved samples. */
    fun push(samples: FloatArray, offset: Int, count: Int) = buffer.write(samples, offset, count)

    /** Drop everything buffered and prebuffer again (track change, user command, rate change). */
    fun flush() {
        buffer.clear()
        flushRequested = true
    }

    /** Fills [out] with the next block. Returns true if it is audio, false if it is silence. */
    fun nextBlock(out: FloatArray): Boolean {
        if (flushRequested) {
            flushRequested = false
            buffer.clear()
            state = State.PREBUFFER
            drift.reset()
            adjuster.reset()
        }
        blocksOut++
        var fill = buffer.fillMs(sampleRate)
        if (state == State.PREBUFFER) {
            if (fill < drift.targetMs) return silence(out)
            state = State.PLAYING
            drift.reset()
            adjuster.reset()
        }
        if (fill < minPlayMs) {
            underruns++
            state = State.PREBUFFER
            return silence(out)
        }
        if (fill > drift.targetMs + maxOverMs) {
            val excess = ((fill - drift.targetMs) * sampleRate / 1000.0).toInt()
            droppedFrames += buffer.skip(excess)
            hardResyncs++
            drift.reset()
            adjuster.reset()
            fill = buffer.fillMs(sampleRate)
        }
        val ppm = drift.update(fill, blockFrames.toDouble() / sampleRate)
        var d = adjuster.delta(blockFrames, ppm)
        if (d > maxDelta) d = maxDelta
        if (d < -blockFrames / 2) d = -blockFrames / 2
        val avail = buffer.availableFrames
        if (blockFrames + d > avail) d = avail - blockFrames
        val need = blockFrames + d
        val got = buffer.read(scratch, 0, need)
        if (got < need) java.util.Arrays.fill(scratch, got * channels, need * channels, 0f)
        adjuster.process(scratch, need, out, blockFrames)
        if (d > 0) droppedFrames += d else insertedFrames += -d
        return true
    }

    private fun silence(out: FloatArray): Boolean {
        java.util.Arrays.fill(out, 0, blockFrames * channels, 0f)
        silentBlocks++
        return false
    }
}
