package dev.walkdac.core

/**
 * Estimates the offset between the sender's monotonic clock and ours from time/time_ack exchanges,
 * assuming a symmetric network path (the Snapcast method: median of the last [window] samples).
 */
class TimeSync(val window: Int = 200) {
    private val offsets = LongArray(window)
    private var n = 0
    private var next = 0

    var minRttNs: Long = Long.MAX_VALUE
        private set
    var lastRttNs: Long = 0
        private set
    val samples: Int get() = n

    /** @param sendNs our clock when the request left, @param recvNs our clock when the reply arrived, @param remoteNs the sender's clock from the reply */
    fun add(sendNs: Long, recvNs: Long, remoteNs: Long) {
        val rtt = recvNs - sendNs
        if (rtt < 0) return
        lastRttNs = rtt
        if (rtt < minRttNs) minRttNs = rtt
        val offset = remoteNs - (sendNs + (rtt / 2))
        offsets[next] = offset
        next = (next + 1) % window
        if (n < window) n++
    }

    /** Median offset (remote - local) in ns, or 0 if no samples yet. */
    val offsetNs: Long
        get() {
            if (n == 0) return 0
            val copy = offsets.copyOf(n)
            copy.sort()
            return copy[n / 2]
        }

    fun remoteNow(localNs: Long): Long = localNs + offsetNs

    fun reset() {
        n = 0
        next = 0
        minRttNs = Long.MAX_VALUE
        lastRttNs = 0
    }
}
