/*
 * ************************************************************************
 *  WavReader.kt
 * *************************************************************************
 * Copyright © 2026 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.vlc.gui.video.dualview.audio

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams the samples of a PCM WAV file (16-bit integer or 32-bit float), mixed down to mono.
 *
 * The data chunk is read to the end of the file when its size is unknown, as in a file whose
 * writer was interrupted before it could fill in the sizes.
 */
class WavReader(input: InputStream) : Closeable {

    private val input = DataInputStream(input)
    val sampleRate: Int
    val channels: Int
    private val bitsPerSample: Int
    private val isFloat: Boolean
    private var remaining = 0L

    init {
        if (readTag() != "RIFF") throw IOException("Not a RIFF file")
        readIntLE()
        if (readTag() != "WAVE") throw IOException("Not a WAVE file")
        var rate = 0
        var channelCount = 0
        var bits = 0
        var format = 0
        while (true) {
            val tag = readTag()
            val size = readIntLE().toLong() and 0xFFFFFFFFL
            if (tag == "fmt ") {
                format = readShortLE()
                channelCount = readShortLE()
                rate = readIntLE()
                readIntLE() // byte rate
                readShortLE() // block align
                bits = readShortLE()
                var consumed = 16L
                if (format == FORMAT_EXTENSIBLE && size >= 40) {
                    readShortLE() // extension size
                    readShortLE() // valid bits
                    readIntLE() // channel mask
                    format = readShortLE() // sub-format GUID starts with the format code
                    consumed += 10
                }
                skipFully(size - consumed + (size and 1))
            } else if (tag == "data") {
                remaining = if (size == 0L || size == 0xFFFFFFFFL) Long.MAX_VALUE else size
                break
            } else {
                skipFully(size + (size and 1))
            }
        }
        if (channelCount <= 0 || rate <= 0) throw IOException("Missing WAV format")
        isFloat = format == FORMAT_FLOAT && bits == 32
        if (!isFloat && (format != FORMAT_PCM || bits != 16)) throw IOException("Unsupported WAV format $format/$bits")
        sampleRate = rate
        channels = channelCount
        bitsPerSample = bits
    }

    /**
     * Reads up to `out.size` mono samples into [out]
     *
     * @return the number of samples read, 0 at the end of the data
     */
    fun read(out: FloatArray): Int {
        val frameBytes = channels * bitsPerSample / 8
        val wanted = minOf(out.size.toLong(), remaining / frameBytes).toInt()
        if (wanted <= 0) return 0
        // Read and convert whole blocks: per-sample reads are slow
        val size = wanted * frameBytes
        if (bytes.size < size) bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(bytes, read, size - read)
            if (count < 0) break
            read += count
        }
        val frames = read / frameBytes
        remaining = if (frames < wanted) 0L else remaining - size
        val buffer = ByteBuffer.wrap(bytes, 0, frames * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = frames * channels
        if (isFloat) {
            if (floats.size < samples) floats = FloatArray(samples)
            buffer.asFloatBuffer().get(floats, 0, samples)
            mix(frames) { floats[it] }
        } else {
            if (shorts.size < samples) shorts = ShortArray(samples)
            buffer.asShortBuffer().get(shorts, 0, samples)
            mix(frames) { shorts[it] / 32768F }
        }
        for (i in 0 until frames) out[i] = mixed[i]
        return frames
    }

    private var bytes = ByteArray(0)
    private var shorts = ShortArray(0)
    private var floats = FloatArray(0)
    private var mixed = FloatArray(0)

    private inline fun mix(frames: Int, sample: (Int) -> Float) {
        if (mixed.size < frames) mixed = FloatArray(frames)
        var index = 0
        for (frame in 0 until frames) {
            var sum = 0F
            for (c in 0 until channels) sum += sample(index++)
            mixed[frame] = sum / channels
        }
    }

    override fun close() = input.close()

    private fun readTag(): String {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readShortLE(): Int {
        val low = input.readUnsignedByte()
        return low or (input.readUnsignedByte() shl 8)
    }

    private fun readIntLE(): Int {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val b2 = input.readUnsignedByte()
        val b3 = input.readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                input.readUnsignedByte()
                left--
            } else left -= skipped
        }
    }

    companion object {
        private const val FORMAT_PCM = 1
        private const val FORMAT_FLOAT = 3
        private const val FORMAT_EXTENSIBLE = 0xFFFE
    }
}
