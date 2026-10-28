/*
 * ************************************************************************
 *  VlcAudioReader.kt
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
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.videolan.libvlc.FactoryManager
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMediaFactory
import org.videolan.resources.VLCInstance
import org.videolan.vlc.gui.video.dualview.DualViewSource
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

private const val TAG = "VLC/DualViewVlcAudio"

/**
 * Reads audio through VLC itself, so it works for anything VLC plays, network shares included.
 *
 * VLC transcodes the first audio track to a WAV file from the very start of the file: when it
 * writes to a file, VLC 3 doesn't trim the audio that precedes a seek target, so a read that
 * starts with a seek would have no reliable timing. Reading the whole track is slow for big
 * network files, so the resulting envelope is cached per video.
 */
class VlcAudioReader(private val context: Context, private val uri: Uri, private val cacheKey: String, private val lengthMs: Long) : AudioEnvelopeSource {

    private val directory = File(context.cacheDir, CACHE_DIRECTORY)
    private val cacheFile = File(directory, "$cacheKey.env")

    override suspend fun read(ranges: List<LongRange>, progress: (Float) -> Unit): List<AudioEnvelope> {
        val envelope = withContext(Dispatchers.IO) { load() } ?: extract(progress).also { withContext(Dispatchers.IO) { save(it) } }
        return ranges.map { envelope.slice(it.first, it.last) }
    }

    private suspend fun extract(progress: (Float) -> Unit): AudioEnvelope = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val wav = File(directory, "$cacheKey.wav")
        val libVLC = VLCInstance.getInstance(context)
        val media = mediaFactory.getFromUri(libVLC, Uri.parse(DualViewSource.withLane(uri.toString(), DualViewSource.LANE_VLC_AUDIO)))
        media.addOption(":no-video")
        media.addOption(":no-spu")
        media.addOption(":no-sout-all")
        media.addOption(":sout=#transcode{acodec=s16l,channels=1,samplerate=$SAMPLE_RATE}:std{access=file,mux=wav,dst=\"${wav.path}\"}")
        val player = MediaPlayer(libVLC)
        val result = CompletableDeferred<Boolean>()
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.EndReached -> result.complete(true)
                MediaPlayer.Event.EncounteredError -> result.complete(false)
                MediaPlayer.Event.TimeChanged -> if (lengthMs > 0) progress((event.timeChanged.toFloat() / lengthMs).coerceIn(0F, 1F))
            }
        }
        player.media = media
        media.release()
        try {
            player.play()
            if (!result.await()) throw IOException("VLC could not read the audio of $uri")
        } finally {
            withContext(NonCancellable) {
                player.setEventListener(null)
                player.stop()
                player.release()
            }
        }
        try {
            buildEnvelope(wav)
        } finally {
            wav.delete()
        }
    }

    private suspend fun buildEnvelope(wav: File): AudioEnvelope {
        WavReader(wav.inputStream().buffered()).use { reader ->
            val steps = ((lengthMs + AudioEnvelope.HOP_MS - 1) / AudioEnvelope.HOP_MS).toInt().coerceAtLeast(1)
            val builder = EnvelopeBuilder(0L, steps * AudioEnvelope.HOP_MS)
            val block = FloatArray(reader.sampleRate)
            var position = 0L
            while (true) {
                coroutineContext.ensureActive()
                val count = reader.read(block)
                if (count <= 0) break
                // Sample n of the stream was played at n / rate
                builder.add(position * 1_000_000L / reader.sampleRate, reader.sampleRate, block, count)
                position += count
            }
            if (position == 0L) throw IOException("VLC found no audio in $uri")
            return builder.build()
        }
    }

    private fun load(): AudioEnvelope? {
        if (!cacheFile.exists()) return null
        return try {
            DataInputStream(cacheFile.inputStream().buffered()).use { input ->
                if (input.readInt() != CACHE_MAGIC || input.readLong() != AudioEnvelope.HOP_MS) return null
                val start = input.readLong()
                val energy = FloatArray(input.readInt())
                for (i in energy.indices) energy[i] = input.readFloat()
                cacheFile.setLastModified(System.currentTimeMillis())
                AudioEnvelope(start, energy)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Dropping unreadable cache ${cacheFile.name}", e)
            cacheFile.delete()
            null
        }
    }

    private fun save(envelope: AudioEnvelope) {
        try {
            directory.mkdirs()
            DataOutputStream(cacheFile.outputStream().buffered()).use { output ->
                output.writeInt(CACHE_MAGIC)
                output.writeLong(AudioEnvelope.HOP_MS)
                output.writeLong(envelope.startMs)
                output.writeInt(envelope.energy.size)
                envelope.energy.forEach { output.writeFloat(it) }
            }
            directory.listFiles { file -> file.name.endsWith(".env") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(CACHE_SIZE)
                    ?.forEach { it.delete() }
        } catch (e: IOException) {
            Log.w(TAG, "Could not cache the audio envelope", e)
        }
    }

    companion object {
        private val mediaFactory = FactoryManager.getFactory(IMediaFactory.factoryId) as IMediaFactory
        private const val SAMPLE_RATE = 8000
        private const val CACHE_DIRECTORY = "dual_view"
        // Bumped whenever envelopes are computed differently, so older caches are dropped
        private const val CACHE_MAGIC = 0x44564532 // DVE2
        private const val CACHE_SIZE = 10
    }
}
