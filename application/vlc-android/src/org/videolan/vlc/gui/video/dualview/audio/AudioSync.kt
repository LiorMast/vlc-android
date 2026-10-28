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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.gui.video.dualview.DualViewTimeline
import org.videolan.vlc.gui.video.dualview.Segment
import java.io.IOException
import kotlin.math.abs
import kotlin.coroutines.coroutineContext

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
 * Finds how far apart the two POVs of a video are by matching their audio. One instance serves
 * one video: what it reads is kept for the next searches.
 *
 * @param length the video's length, in ms
 */
class AudioSync(context: Context, source: Source, private val length: Long) {

    /**
     * @param playbackUri the address VLC plays
     * @param locationUri where the video is, as the media library knows it
     * @param cacheKey identifies the video in caches
     */
    class Source(val playbackUri: Uri, val locationUri: Uri, val cacheKey: String)

    sealed class Result {
        data class Found(val offset: Long, val score: Float) : Result()
        data class Segments(val segments: List<Segment>) : Result()
        object NoMatch : Result()
        object TooShort : Result()
        object Unreadable : Result()
    }

    /**
     * The audio read so far, for the waveform strips too
     */
    val cache = AudioEnvelopeCache(VideoAudio(context.applicationContext, source, length))

    /**
     * Compares several stretches across POV 1 with POV 2, searching widely around the timeline's
     * first offset: the split is only a guess
     *
     * @param onReading called with the fraction of audio read so far, possibly from another thread
     */
    suspend fun findOffset(timeline: DualViewTimeline, onReading: (Float) -> Unit): Result = guarded {
        val base = snap(timeline.segments[0].offset)
        val start = timeline.start
        val split = timeline.split
        val window = snap(minOf(WHOLE_WINDOW, (split - start) / 4))
        val searched = snap(maxOf(MIN_WHOLE_RANGE, timeline.length / 20).coerceAtMost(MAX_WHOLE_RANGE))
        if (window < MIN_WINDOW) return@guarded Result.TooShort
        // Spread the windows where every offset searched stays inside POV 2
        val first = maxOf(start, timeline.split + searched - base)
        val last = minOf(split - window, timeline.end - base - window - searched)
        val starts = (if (last > first) listOf(1, 3, 5).map { first + (last - first) * it / 6 }
        else listOf(1, 2, 3).map { start + (split - start) * it / 4 - window / 2 }).map { snap(it.coerceIn(start, split - window)) }
        val ranges = starts.flatMap { pov1Start ->
            val pov2Start = maxOf(pov1Start + base - searched, ceil(split))
            val pov2End = minOf(pov1Start + base + window + searched, snap(timeline.end))
            if (pov2End - pov2Start < window + CutFinder.MIN_SEARCH) emptyList() else listOf(pov1Start..pov1Start + window, pov2Start..pov2End)
        }
        if (ranges.isEmpty()) return@guarded Result.TooShort
        val envelopes = cache.get(ranges, onReading)
        val match = withContext(Dispatchers.Default) {
            AudioOffsetMatcher.match(envelopes.chunked(2).map { AudioOffsetMatcher.Window(it[0], it[1]) }, base, searched)
        }
        log("whole file around $base ±$searched ms: $match")
        if (match != null && match.accepted) Result.Found(match.offsetMs, match.score) else Result.NoMatch
    }

    /**
     * Matches the [LOCAL_WINDOW] of POV 1 around [pov1Time], near the offset the timeline has there,
     * then across all of POV 2 if that fails: after a cut the offset can be anything
     */
    suspend fun syncAt(timeline: DualViewTimeline, pov1Time: Long, onReading: (Float) -> Unit): Result = guarded {
        val window = snap(minOf(LOCAL_WINDOW, timeline.split - timeline.start, timeline.end - timeline.split))
        if (window < MIN_WINDOW) return@guarded Result.TooShort
        val from = snap((pov1Time - window / 2).coerceIn(timeline.start, timeline.split - window))
        val base = timeline.offsetAt(pov1Time)
        val local = matchAround(timeline, from, window, base, CutFinder.LOCAL_RANGE, onReading)
        if (local != null && local.accepted) return@guarded Result.Found(local.offsetMs, local.score)
        val range = CutFinder.wholeRange(from, window, base, timeline.split, timeline.end)
        val wide = matchAround(timeline, from, window, base, range, onReading)
        if (wide != null && wide.accepted) Result.Found(wide.offsetMs, wide.score) else Result.NoMatch
    }

    /**
     * Where, in POV 1 between [from] and [to], [oldOffset] gives way to [newOffset]
     */
    suspend fun findCut(timeline: DualViewTimeline, from: Long, to: Long, oldOffset: Long, newOffset: Long, onReading: (Float) -> Unit): Long? = try {
        val pov1 = snap(from)..ceil(to)
        val low = minOf(oldOffset, newOffset)
        val high = maxOf(oldOffset, newOffset)
        val pov2 = maxOf(ceil(timeline.split), snap(pov1.first + low - CUT_MARGIN))..minOf(snap(timeline.end), ceil(pov1.last + high + CUT_MARGIN))
        if (pov2.last <= pov2.first) null
        else {
            val (pov1Envelope, pov2Envelope) = cache.get(listOf(pov1, pov2), onReading)
            withContext(Dispatchers.Default) { CutFinder.findCut(pov1Envelope, pov2Envelope, oldOffset, newOffset) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Can't read the audio to find the cut", e)
        null
    }

    /**
     * Finds the segments of the whole video
     *
     * @param onReading called with the fraction of audio read so far, then of the video matched
     */
    suspend fun scan(timeline: DualViewTimeline, onReading: (Float) -> Unit, onMatching: (Float) -> Unit): Result = guarded {
        val pov1 = ceil(timeline.start)..snap(timeline.split)
        val pov2 = ceil(timeline.split)..snap(timeline.end)
        val (pov1Envelope, pov2Envelope) = cache.get(listOf(pov1, pov2), onReading)
        val segments = withContext(Dispatchers.Default) {
            CutFinder.scan(pov1Envelope, pov2Envelope, timeline.start, timeline.split, timeline.end, timeline.segments[0].offset, onMatching) { isActive }
        }
        coroutineContext.ensureActive()
        log("scan: $segments")
        if (segments == null) Result.NoMatch else Result.Segments(segments)
    }

    /**
     * Reads all of the video's audio into [cache] a block at a time, from the one holding [around]
     * outwards, so what's near the current moment comes first. Features asking for audio meanwhile
     * get in between blocks and read only what's still missing.
     *
     * @param onProgress the fraction of the audio read so far
     * @return whether all of it could be read
     */
    suspend fun prefetch(around: Long, onProgress: (Float) -> Unit): Boolean {
        val whole = 0L..snap(length)
        if (whole.last <= 0) return false
        val count = ((whole.last + PREFETCH_BLOCK - 1) / PREFETCH_BLOCK).toInt()
        val first = (around.coerceIn(0L, whole.last - 1) / PREFETCH_BLOCK).toInt()
        // first, first + 1, first - 1, first + 2...
        val order = (0 until count).sortedBy { abs(it - first) * 2 - if (it > first) 1 else 0 }
        for (index in order) {
            val block = index * PREFETCH_BLOCK..minOf((index + 1) * PREFETCH_BLOCK, whole.last)
            if (!cache.has(block)) try {
                cache.get(listOf(block))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unreadable: the features asking for audio say so
                Log.w(TAG, "Can't read the audio ahead", e)
                return false
            }
            onProgress(cache.covered(whole).toFloat() / (whole.last - whole.first))
        }
        return true
    }

    private suspend fun matchAround(timeline: DualViewTimeline, from: Long, window: Long, base: Long, searched: Long, onReading: (Float) -> Unit): AudioOffsetMatcher.Match? {
        val pov2 = maxOf(ceil(timeline.split), snap(from + base - searched))..minOf(snap(timeline.end), ceil(from + base + window + searched))
        if (pov2.last - pov2.first < window + CutFinder.MIN_SEARCH) return null
        val started = SystemClock.elapsedRealtime()
        val (pov1Envelope, pov2Envelope) = cache.get(listOf(from..from + window, pov2), onReading)
        val readAt = SystemClock.elapsedRealtime()
        val match = withContext(Dispatchers.Default) {
            CutFinder.matchWindow(pov1Envelope, pov2Envelope, from, window, base, searched, timeline.split, timeline.end)
        }
        log("around $from: $base ±$searched ms: $match. Read in ${readAt - started} ms, matched in ${SystemClock.elapsedRealtime() - readAt} ms")
        return match
    }

    /**
     * Runs [block], an audio that can't be read giving [Result.Unreadable]
     */
    private suspend fun guarded(block: suspend () -> Result): Result = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "Can't read this audio", e)
        Result.Unreadable
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Can't read this audio", e)
        Result.Unreadable
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Audio match $message")
    }

    private fun snap(time: Long) = CutFinder.snap(time)

    private fun ceil(time: Long) = CutFinder.ceil(time)

    companion object {
        private const val WHOLE_WINDOW = 20_000L
        private const val MIN_WHOLE_RANGE = 30_000L
        private const val MAX_WHOLE_RANGE = 120_000L
        private const val LOCAL_WINDOW = 15_000L
        private const val MIN_WINDOW = 3_000L
        // POV 2 audio read around both offsets' stretches when looking for a cut
        private const val CUT_MARGIN = 1_000L
        // Read ahead a minute at a time: AndroidAudioReader decodes 4 chunks of 15 s at once
        private const val PREFETCH_BLOCK = 60_000L
    }
}

/**
 * A video's audio, read by Android when it can, else by VLC
 */
private class VideoAudio(private val context: Context, private val source: AudioSync.Source, private val length: Long) : AudioEnvelopeSource {

    @Volatile
    private var androidFailed = false

    override suspend fun read(ranges: List<LongRange>, progress: (Float) -> Unit): List<AudioEnvelope> {
        if (!androidFailed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && AndroidAudioReader.canRead(source.locationUri)) try {
            return AndroidAudioReader(context, source.locationUri).read(ranges, progress)
        } catch (e: IOException) {
            Log.w(TAG, "Android can't read this audio, trying VLC", e)
            androidFailed = true
        }
        return VlcAudioReader(context, source.playbackUri, source.cacheKey, length).read(ranges, progress)
    }
}
