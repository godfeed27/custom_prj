package dev.walkdac.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One audio frame header as sent by walkdac_sender.py (28 bytes, little-endian). */
data class FrameHeader(
    val version: Int,
    val codec: Int,
    val seq: Long,
    val captureNs: Long,
    val sampleRate: Int,
    val frames: Int,
    val bits: Int,
    val channels: Int,
    val payloadLen: Int,
)

class ProtocolException(message: String) : Exception(message)

object Protocol {
    const val VERSION = 1
    const val HEADER_SIZE = 28
    const val CODEC_S16 = 0
    const val CODEC_S24 = 1
    const val CODEC_F32 = 2
    const val MAX_PAYLOAD = 4 * 1024 * 1024
    const val MAX_FRAMES = 65535

    const val DEFAULT_AUDIO_PORT = 7700
    const val DEFAULT_CONTROL_PORT = 7701
    const val DEFAULT_BEACON_PORT = 7702

    fun bytesPerSample(codec: Int): Int = when (codec) {
        CODEC_S16 -> 2
        CODEC_S24 -> 3
        CODEC_F32 -> 4
        else -> throw ProtocolException("unknown codec $codec")
    }

    fun codecName(codec: Int): String = when (codec) {
        CODEC_S16 -> "pcm_s16"
        CODEC_S24 -> "pcm_s24"
        CODEC_F32 -> "pcm_f32"
        else -> "codec$codec"
    }

    /** Parses and validates a header. Throws [ProtocolException] on anything we cannot trust. */
    fun parseHeader(buf: ByteArray, offset: Int = 0): FrameHeader {
        if (buf.size - offset < HEADER_SIZE) throw ProtocolException("short header")
        if (buf[offset] != 'W'.code.toByte() || buf[offset + 1] != 'D'.code.toByte()) {
            throw ProtocolException("bad magic")
        }
        val bb = ByteBuffer.wrap(buf, offset, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(offset + 2)
        val version = bb.get().toInt() and 0xFF
        val codec = bb.get().toInt() and 0xFF
        val seq = bb.int.toLong() and 0xFFFFFFFFL
        val captureNs = bb.long
        val rate = bb.int
        val frames = bb.short.toInt() and 0xFFFF
        val bits = bb.get().toInt() and 0xFF
        val channels = bb.get().toInt() and 0xFF
        val payloadLen = bb.int
        if (version != VERSION) throw ProtocolException("unsupported version $version")
        val bps = bytesPerSample(codec)
        if (channels != 2) throw ProtocolException("unsupported channel count $channels")
        if (rate < 8000 || rate > 384000) throw ProtocolException("bad sample rate $rate")
        if (payloadLen < 0 || payloadLen > MAX_PAYLOAD) throw ProtocolException("bad payload length $payloadLen")
        if (payloadLen != frames * channels * bps) {
            throw ProtocolException("payload length $payloadLen does not match $frames frames of $codec")
        }
        return FrameHeader(version, codec, seq, captureNs, rate, frames, bits, channels, payloadLen)
    }
}

/** PCM payload decoding into interleaved float samples in [-1, 1]. */
object Pcm {
    /** Decodes [len] bytes of [payload] into [out] starting at [outOffset]. Returns the number of samples written. */
    fun decode(codec: Int, payload: ByteArray, len: Int, out: FloatArray, outOffset: Int = 0): Int {
        when (codec) {
            Protocol.CODEC_S16 -> {
                val n = len / 2
                var p = 0
                for (i in 0 until n) {
                    val v = ((payload[p].toInt() and 0xFF) or (payload[p + 1].toInt() shl 8)).toShort().toInt()
                    out[outOffset + i] = v / 32767f
                    p += 2
                }
                return n
            }
            Protocol.CODEC_S24 -> {
                val n = len / 3
                var p = 0
                for (i in 0 until n) {
                    // the top byte is sign-extended by toInt(), the lower two are masked
                    val v = (payload[p].toInt() and 0xFF) or ((payload[p + 1].toInt() and 0xFF) shl 8) or (payload[p + 2].toInt() shl 16)
                    out[outOffset + i] = v / 8388607f
                    p += 3
                }
                return n
            }
            Protocol.CODEC_F32 -> {
                val n = len / 4
                val fb = ByteBuffer.wrap(payload, 0, len).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                fb.get(out, outOffset, n)
                return n
            }
            else -> throw ProtocolException("unknown codec $codec")
        }
    }
}
