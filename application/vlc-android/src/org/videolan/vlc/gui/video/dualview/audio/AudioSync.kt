/*
 * ************************************************************************
 *  AudioSync.kt
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
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.gui.video.dualview.DualViewTimeline
import java.io.IOException

private const val TAG = "VLC/DualViewAudioSync"

/**
 * Audio of a video, as loudness envelopes of the stretches asked for
 */
interface AudioEnvelopeSource {
    /**
     * @param ranges stretches to read, in ms, each one from its `first` (included) to its `last` (excluded)
     * @param progress called with the fraction read so far, possibly from another thread
     */
    suspend fun read(ranges: List<LongRange>, progress: (Float) -> Unit): List<AudioEnvelope>
}

/**
 * Finds how far apart the two POVs of a video are by matching their audio
 */
class AudioSync(private val context: Context) {

    /**
     * @param playbackUri the address VLC plays
     * @param locationUri where the video is, as the media library knows it
     * @param cacheKey identifies the video in caches
     */
    class Source(val playbackUri: Uri, val locationUri: Uri, val cacheKey: String)

    enum class Mode {
        /**
         * Compare several stretches across POV 1, searching widely: the split is only a guess
         */
        WHOLE,

        /**
         * Compare the audio around one moment, searching near the current offset
         */
        AROUND
    }

    sealed class Result {
        data class Found(val offset: Long, val score: Float) : Result()
        object NoMatch : Result()
        object TooShort : Result()
        object Unreadable : Result()
    }

    /**
     * @param pov1Time moment of POV 1 to match around, for [Mode.AROUND]
     * @param onReading called with the fraction of audio read so far, possibly from another thread
     */
    suspend fun findOffset(source: Source, timeline: DualViewTimeline, mode: Mode, pov1Time: Long, onReading: (Float) -> Unit): Result {
        val base = snap(timeline.offset)
        val length = timeline.length
        val split = timeline.split
        val window = snap(when (mode) {
            Mode.WHOLE -> minOf(WHOLE_WINDOW, split / 4)
            Mode.AROUND -> minOf(AROUND_WINDOW, split / 2, (length - split) / 2)
        })
        val searched = snap(when (mode) {
            Mode.WHOLE -> maxOf(MIN_WHOLE_RANGE, length / 20).coerceAtMost(MAX_WHOLE_RANGE)
            Mode.AROUND -> AROUND_RANGE
        })
        if (window < MIN_WINDOW) return Result.TooShort
        val centers = when (mode) {
            Mode.WHOLE -> {
                // Spread the windows where every offset searched stays inside POV 2
                val first = maxOf(0L, split + searched - base)
                val last = minOf(split - window, length - base - window - searched)
                if (last > first) listOf(1, 3, 5).map { first + (last - first) * it / 6 + window / 2 }
                else listOf(split / 4, split / 2, split * 3 / 4)
            }
            Mode.AROUND -> listOf(pov1Time)
        }
        // Pairs of [start, end) stretches: one of POV 1, and where to look for it in POV 2
        val ranges = centers.flatMap { center ->
            val pov1Start = snap((center - window / 2).coerceIn(0L, split - window))
            // Stay on the step grid: the first step of POV 2 starts at or after the split
            val pov2Start = maxOf(pov1Start + base - searched, ceil(split))
            val pov2End = minOf(pov1Start + base + window + searched, snap(length))
            if (pov2End - pov2Start < window + MIN_SEARCH) emptyList<LongRange>() else listOf(pov1Start..pov1Start + window, pov2Start..pov2End)
        }
        if (ranges.isEmpty()) return Result.TooShort
        val started = SystemClock.elapsedRealtime()
        // The windows' stretches overlap: read each moment once
        val merged = merge(ranges)
        val mergedEnvelopes = read(source, length, merged, onReading) ?: return Result.Unreadable
        val envelopes = ranges.map { range -> mergedEnvelopes[merged.indexOfFirst { it.first <= range.first && range.last <= it.last }].slice(range.first, range.last) }
        val readAt = SystemClock.elapsedRealtime()
        val match = withContext(Dispatchers.Default) {
            val windows = envelopes.chunked(2).map { AudioOffsetMatcher.Window(it[0], it[1]) }
            AudioOffsetMatcher.match(windows, base, searched)
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Audio match in $mode around $base ±$searched ms: $match. " +
                "Read ${merged.sumOf { it.last - it.first } / 1000} s of audio in ${readAt - started} ms, matched in ${SystemClock.elapsedRealtime() - readAt} ms")
        return if (match != null && match.accepted) Result.Found(match.offsetMs, match.score) else Result.NoMatch
    }

    private suspend fun read(source: Source, length: Long, ranges: List<LongRange>, onReading: (Float) -> Unit): List<AudioEnvelope>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && AndroidAudioReader.canRead(source.locationUri)) try {
            return AndroidAudioReader(context, source.locationUri).read(ranges, onReading)
        } catch (e: IOException) {
            Log.w(TAG, "Android can't read this audio, trying VLC", e)
        }
        return try {
            VlcAudioReader(context, source.playbackUri, source.cacheKey, length).read(ranges, onReading)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "VLC can't read this audio", e)
            null
        } catch (e: IllegalStateException) {
            Log.w(TAG, "VLC can't read this audio", e)
            null
        }
    }

    /**
     * [ranges] with the overlapping ones joined, in order
     */
    private fun merge(ranges: List<LongRange>): List<LongRange> {
        val merged = ArrayList<LongRange>()
        for (range in ranges.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last) merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
            else merged.add(range)
        }
        return merged
    }

    private fun snap(time: Long) = time.floorDiv(AudioEnvelope.HOP_MS) * AudioEnvelope.HOP_MS

    private fun ceil(time: Long) = snap(time + AudioEnvelope.HOP_MS - 1)

    companion object {
        private const val WHOLE_WINDOW = 20_000L
        private const val MIN_WHOLE_RANGE = 30_000L
        private const val MAX_WHOLE_RANGE = 120_000L
        private const val AROUND_WINDOW = 30_000L
        private const val AROUND_RANGE = 30_000L
        private const val MIN_WINDOW = 3_000L
        private const val MIN_SEARCH = 1_000L
    }
}
