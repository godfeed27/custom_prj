package dev.walkdac.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import dev.walkdac.core.PlaybackPipeline

/**
 * Owns the AudioTrack and the thread that pulls 10 ms blocks from the [PlaybackPipeline].
 * One engine per sample rate: a rate change means stop() and a new engine.
 */
class AudioEngine(val sampleRate: Int, targetMs: Double) {
    val channels = 2
    val blockFrames = maxOf(64, sampleRate / 100) // 10 ms
    val pipeline = PlaybackPipeline(sampleRate, channels, blockFrames, targetMs)

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var framesWritten: Long = 0
        private set
    @Volatile var trackUnderruns: Int = 0
        private set
    @Volatile var outputLatencyMs: Double = 0.0
        private set
    @Volatile var lastError: String? = null
        private set
    /** False once the output thread has exited (error, dead AudioTrack after an audioserver restart, or stop()). */
    @Volatile var alive: Boolean = false
        private set
    val trackBufferFrames: Int get() = track?.bufferSizeInFrames ?: 0

    fun routedDeviceType(): Int = track?.routedDevice?.type ?: 0

    fun start() {
        if (running) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val wantBytes = blockFrames * channels * 4 * 8 // 80 ms of float stereo
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBytes, wantBytes))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            throw IllegalStateException("AudioTrack init failed at $sampleRate Hz")
        }
        track = t
        running = true
        alive = true
        thread = Thread({ loop(t) }, "walkdac-audio-out").apply { start() }
    }

    private fun loop(t: AudioTrack) {
        val block = FloatArray(blockFrames * channels)
        val ts = AudioTimestamp()
        var blocks = 0L
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            t.play()
            while (running) {
                pipeline.nextBlock(block)
                var off = 0
                while (off < block.size && running) {
                    val n = t.write(block, off, block.size - off, AudioTrack.WRITE_BLOCKING)
                    if (n < 0) throw IllegalStateException("AudioTrack.write returned $n")
                    off += n
                }
                framesWritten += blockFrames
                blocks++
                if (blocks % 50 == 0L) { // every 500 ms
                    trackUnderruns = t.underrunCount
                    if (t.getTimestamp(ts)) {
                        val elapsedFrames = (System.nanoTime() - ts.nanoTime) * sampleRate / 1e9
                        val pending = framesWritten - ts.framePosition - elapsedFrames
                        outputLatencyMs = (pending * 1000.0 / sampleRate).coerceAtLeast(0.0)
                    }
                }
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
            Log.e(TAG, "audio thread died", e)
        } finally {
            alive = false
            try { t.pause(); t.flush() } catch (e: Exception) { /* ignore */ }
            t.release()
        }
    }

    fun flush() = pipeline.flush()

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        track = null
    }

    companion object {
        private const val TAG = "WalkDAC.Audio"

        fun mixerRate(am: AudioManager): Int =
            am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0

        fun mixerFramesPerBuffer(am: AudioManager): Int =
            am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
    }
}
