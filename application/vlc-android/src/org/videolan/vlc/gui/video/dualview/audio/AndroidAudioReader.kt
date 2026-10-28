/*
 * ************************************************************************
 *  AndroidAudioReader.kt
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

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.videolan.vlc.gui.video.dualview.DualViewSource
import java.io.IOException
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Reads audio with Android's own extractor and decoders. It only decodes the requested
 * stretches and places every sample by its timestamp, so the timing is exact.
 *
 * Handles local files, content URIs and HTTP(S); [read] throws an [IOException] for anything it
 * can't open or decode.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class AndroidAudioReader(private val context: Context, private val uri: Uri) : AudioEnvelopeSource {

    override suspend fun read(ranges: List<LongRange>, progress: (Float) -> Unit): List<AudioEnvelope> = withContext(Dispatchers.IO) {
        val total = ranges.sumOf { it.last - it.first }.coerceAtLeast(1L).toFloat()
        val done = AtomicLong()
        // Every buffer handed to or taken from the decoder is a round trip to the media service, so
        // decoding is bound by their latency, not by the CPU: decode several chunks at once.
        // Each decoder streams on its own lane of a proxy on this device, see DualViewSource
        val slots = Channel<Int>(PARALLEL_DECODERS).apply { for (slot in 1..PARALLEL_DECODERS) trySend(slot) }
        val parts = ranges.map { range ->
            (range.first until range.last step CHUNK_MS).map { start ->
                val chunk = start..minOf(start + CHUNK_MS, range.last)
                async {
                    val slot = slots.receive()
                    try {
                        val extractor = open(DualViewSource.audioLane(slot))
                        try {
                            decode(extractor, chunk).also { progress(done.addAndGet(chunk.last - chunk.first) / total) }
                        } finally {
                            extractor.release()
                        }
                    } finally {
                        slots.trySend(slot)
                    }
                }
            }
        }
        ranges.mapIndexed { index, range ->
            val energies = parts[index].awaitAll().map { it.energy }
            val energy = FloatArray(energies.sumOf { it.size })
            var position = 0
            for (part in energies) {
                part.copyInto(energy, position)
                position += part.size
            }
            AudioEnvelope(range.first, energy)
        }
    }

    private fun open(lane: String): MediaExtractor {
        val extractor = MediaExtractor()
        try {
            when (uri.scheme) {
                "file" -> extractor.setDataSource(uri.path ?: throw IOException("No path in $uri"))
                "content" -> extractor.setDataSource(context, uri, null)
                else -> extractor.setDataSource(DualViewSource.withLane(uri.toString(), lane), emptyMap())
            }
            extractor.selectTrack(audioTrack(extractor))
        } catch (e: Exception) {
            extractor.release()
            throw if (e is IOException) e else IOException("Can't open $uri", e)
        }
        return extractor
    }

    /**
     * Decodes [range] (ms) of the selected track into an envelope
     */
    private suspend fun decode(extractor: MediaExtractor, range: LongRange): AudioEnvelope {
        val format = extractor.getTrackFormat(audioTrack(extractor))
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("Unknown audio format")
        val codec = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            throw IOException("No Android decoder for $mime", e)
        }
        val builder = EnvelopeBuilder(range.first, range.last)
        val endUs = range.last * 1000
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var isFloat = false
        var shorts = ShortArray(0)
        var floats = FloatArray(0)
        val info = MediaCodec.BufferInfo()
        try {
            extractor.seekTo(((range.first - PREROLL_MS) * 1000).coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                coroutineContext.ensureActive()
                var fed = false
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(0L)
                    if (index >= 0) {
                        fed = true
                        val buffer = codec.getInputBuffer(index) ?: throw IOException("No decoder input buffer")
                        val size = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > endUs) {
                            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }
                // Only wait for the decoder when there was nothing to feed it
                val index = codec.dequeueOutputBuffer(info, if (fed) 0L else TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = codec.outputFormat
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    isFloat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && output.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (index >= 0) {
                    if (info.size > 0 && channels > 0) {
                        val buffer = codec.getOutputBuffer(index) ?: throw IOException("No decoder output buffer")
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        // Bulk copies: reading the buffer sample by sample is slow
                        if (isFloat) {
                            val count = info.size / 4
                            if (floats.size < count) floats = FloatArray(count)
                            buffer.asFloatBuffer().get(floats, 0, count)
                            builder.addFloat(info.presentationTimeUs, sampleRate, floats, count / channels, channels)
                        } else {
                            val count = info.size / 2
                            if (shorts.size < count) shorts = ShortArray(count)
                            buffer.asShortBuffer().get(shorts, 0, count)
                            builder.add16(info.presentationTimeUs, sampleRate, shorts, count / channels, channels)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } catch (e: IllegalStateException) {
            throw IOException("Android decoder failed", e)
        } finally {
            try {
                codec.stop()
            } catch (ignored: IllegalStateException) {
            }
            codec.release()
        }
        return builder.build()
    }

    /**
     * The first audio track, the one VLC plays by default
     */
    private fun audioTrack(extractor: MediaExtractor) = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    } ?: throw IOException("No audio track Android can read in $uri")

    companion object {
        private const val TIMEOUT_US = 10_000L
        // Decode a bit earlier so the decoder has settled when the range starts
        private const val PREROLL_MS = 200L
        // On the step grid, so the chunks' envelopes join seamlessly
        private const val CHUNK_MS = 15_000L
        private const val PARALLEL_DECODERS = 4

        fun canRead(uri: Uri) = uri.scheme in arrayOf("file", "content", "http", "https")
    }
}
