package org.videolan.vlc.gui.video.dualview.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(JUnit4::class)
class WavReaderTest {

    private fun chunk(tag: String, body: ByteArray, declaredSize: Int = body.size): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag.toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(declaredSize).array())
        out.write(body)
        if (body.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun format(code: Int, channels: Int, rate: Int, bits: Int) = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(code.toShort()).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * bits / 8)
            .putShort((channels * bits / 8).toShort()).putShort(bits.toShort()).array()

    private fun wav(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray(Charsets.US_ASCII))
        chunks.forEach { body.write(it) }
        return chunk("RIFF", body.toByteArray())
    }

    @Test
    fun readsStereo16BitPcmAfterOtherChunks() {
        val samples = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(16384).putShort(0) // frame 1: 0.5 and 0
                .putShort(-32768).putShort(-32768) // frame 2: -1 and -1
                .array()
        val bytes = wav(chunk("fmt ", format(1, 2, 8000, 16)), chunk("LIST", "odd".toByteArray()), chunk("data", samples))
        WavReader(ByteArrayInputStream(bytes)).use { reader ->
            assertEquals(8000, reader.sampleRate)
            assertEquals(2, reader.channels)
            val out = FloatArray(10)
            assertEquals(2, reader.read(out))
            assertArrayEquals(floatArrayOf(0.25F, -1F), out.copyOf(2), 0.0001F)
            assertEquals(0, reader.read(out))
        }
    }

    @Test
    fun readsToTheEndWhenTheDataSizeIsUnknown() {
        val samples = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.5F).putFloat(-0.25F).putFloat(1F).array()
        // An interrupted writer leaves the size at 0
        val bytes = wav(chunk("fmt ", format(3, 1, 16000, 32)), chunk("data", samples, declaredSize = 0))
        WavReader(ByteArrayInputStream(bytes)).use { reader ->
            val out = FloatArray(2)
            assertEquals(2, reader.read(out))
            assertArrayEquals(floatArrayOf(0.5F, -0.25F), out, 0.0001F)
            assertEquals(1, reader.read(out))
            assertEquals(1F, out[0], 0.0001F)
            assertEquals(0, reader.read(out))
        }
    }
}
