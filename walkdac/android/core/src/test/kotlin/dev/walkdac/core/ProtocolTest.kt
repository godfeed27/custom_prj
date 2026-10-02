package dev.walkdac.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolTest {
    private fun header(codec: Int, frames: Int, payloadLen: Int, rate: Int = 48000, seq: Long = 5, channels: Int = 2, version: Int = 1): ByteArray {
        val bb = ByteBuffer.allocate(Protocol.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.put('W'.code.toByte()); bb.put('D'.code.toByte())
        bb.put(version.toByte()); bb.put(codec.toByte())
        bb.putInt(seq.toInt()); bb.putLong(123456789L); bb.putInt(rate)
        bb.putShort(frames.toShort()); bb.put(24.toByte()); bb.put(channels.toByte()); bb.putInt(payloadLen)
        return bb.array()
    }

    @Test fun parsesHeaderLikeTheSender() {
        val h = Protocol.parseHeader(header(Protocol.CODEC_S24, 480, 480 * 2 * 3, seq = 0xFFFFFFFEL))
        assertEquals(1, h.version)
        assertEquals(Protocol.CODEC_S24, h.codec)
        assertEquals(0xFFFFFFFEL, h.seq)
        assertEquals(123456789L, h.captureNs)
        assertEquals(48000, h.sampleRate)
        assertEquals(480, h.frames)
        assertEquals(2, h.channels)
        assertEquals(2880, h.payloadLen)
    }

    @Test fun rejectsBadFrames() {
        assertThrows(ProtocolException::class.java) { Protocol.parseHeader(header(Protocol.CODEC_S24, 480, 2879)) }
        assertThrows(ProtocolException::class.java) { Protocol.parseHeader(header(7, 480, 2880)) }
        assertThrows(ProtocolException::class.java) { Protocol.parseHeader(header(Protocol.CODEC_S16, 480, 1920, version = 2)) }
        assertThrows(ProtocolException::class.java) { Protocol.parseHeader(header(Protocol.CODEC_S16, 480, 960, channels = 1)) }
        val bad = header(Protocol.CODEC_S16, 480, 1920); bad[0] = 'X'.code.toByte()
        assertThrows(ProtocolException::class.java) { Protocol.parseHeader(bad) }
    }

    @Test fun decodesPcmFormats() {
        val out = FloatArray(8)
        // s16: 0, 16384, -16384, 32767
        val s16 = byteArrayOf(0, 0, 0, 0x40, 0, 0xC0.toByte(), 0xFF.toByte(), 0x7F)
        assertEquals(4, Pcm.decode(Protocol.CODEC_S16, s16, s16.size, out))
        assertEquals(0f, out[0], 1e-6f); assertEquals(16384f / 32767f, out[1], 1e-6f)
        assertEquals(-16384f / 32767f, out[2], 1e-6f); assertEquals(1f, out[3], 1e-6f)
        // s24 little-endian: +8388607, -8388607, -1
        val s24 = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x01, 0x00, 0x80.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(3, Pcm.decode(Protocol.CODEC_S24, s24, s24.size, out))
        assertEquals(1f, out[0], 1e-7f); assertEquals(-1f, out[1], 1e-7f); assertEquals(-1f / 8388607f, out[2], 1e-9f)
        val f32 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f).putFloat(-0.5f).array()
        assertEquals(2, Pcm.decode(Protocol.CODEC_F32, f32, 8, out, 2))
        assertEquals(0.25f, out[2], 0f); assertEquals(-0.5f, out[3], 0f)
    }
}
