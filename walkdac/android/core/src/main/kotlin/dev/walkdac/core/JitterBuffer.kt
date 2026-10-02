package dev.walkdac.core

/**
 * Ring buffer of interleaved float samples. Single producer (network thread), single consumer (audio thread).
 * When full, the oldest audio is dropped so a stalled consumer never leaks memory.
 */
class JitterBuffer(val capacityFrames: Int, val channels: Int) {
    private val buf = FloatArray(capacityFrames * channels)
    private var readPos = 0 // in samples
    private var count = 0 // in samples

    /** Frames dropped because the buffer was full. */
    var overflowFrames: Long = 0
        private set

    val availableFrames: Int
        @Synchronized get() = count / channels

    fun fillMs(sampleRate: Int): Double = availableFrames * 1000.0 / sampleRate

    @Synchronized
    fun write(src: FloatArray, offset: Int, samples: Int) {
        var toWrite = samples
        var srcPos = offset
        val cap = buf.size
        if (toWrite > cap) {
            // more than the whole buffer: keep only the newest part
            srcPos += toWrite - cap
            overflowFrames += (toWrite - cap) / channels
            toWrite = cap
        }
        val free = cap - count
        if (toWrite > free) {
            val drop = toWrite - free
            readPos = (readPos + drop) % cap
            count -= drop
            overflowFrames += drop / channels
        }
        var writePos = (readPos + count) % cap
        var remaining = toWrite
        while (remaining > 0) {
            val chunk = minOf(remaining, cap - writePos)
            System.arraycopy(src, srcPos, buf, writePos, chunk)
            writePos = (writePos + chunk) % cap
            srcPos += chunk
            remaining -= chunk
        }
        count += toWrite
    }

    /** Reads up to [frames] frames into [dst]. Returns the number of frames actually read. */
    @Synchronized
    fun read(dst: FloatArray, offset: Int, frames: Int): Int {
        val samples = minOf(frames * channels, count)
        var dstPos = offset
        var remaining = samples
        val cap = buf.size
        while (remaining > 0) {
            val chunk = minOf(remaining, cap - readPos)
            System.arraycopy(buf, readPos, dst, dstPos, chunk)
            readPos = (readPos + chunk) % cap
            dstPos += chunk
            remaining -= chunk
        }
        count -= samples
        return samples / channels
    }

    /** Drops the oldest [frames] frames. Returns how many were dropped. */
    @Synchronized
    fun skip(frames: Int): Int {
        val samples = minOf(frames * channels, count)
        readPos = (readPos + samples) % buf.size
        count -= samples
        return samples / channels
    }

    @Synchronized
    fun clear() {
        readPos = 0
        count = 0
    }
}
