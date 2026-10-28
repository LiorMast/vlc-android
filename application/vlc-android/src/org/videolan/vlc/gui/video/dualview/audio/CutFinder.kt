/*
 * ************************************************************************
 *  CutFinder.kt
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

import org.videolan.vlc.gui.video.dualview.Segment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Finds the offsets of a video whose recordings were cut, from the loudness envelopes of its two
 * POVs: each stretch of POV 1 is matched on its own, and the moment where one offset gives way to
 * the next is found from how well each explains the sounds around it.
 *
 * All times are in ms, on the [AudioEnvelope.HOP_MS] grid where envelopes are concerned.
 */
object CutFinder {

    /**
     * The offset found for the POV 1 stretch starting at [pov1Time], null if none was accepted
     */
    data class WindowMatch(val pov1Time: Long, val offset: Long?)

    /**
     * Consecutive windows agreeing on [offset], from window [first] to [last] (indexes)
     */
    data class Run(val first: Int, val last: Int, val offset: Long)

    const val MIN_SEARCH = 1_000L
    const val SCAN_WINDOW = 12_000L
    const val SCAN_STEP = 20_000L
    const val LOCAL_RANGE = 30_000L
    const val RUN_TOLERANCE = 100L

    /**
     * Matches [pov1] from [from] for [window] ms against [pov2], searching offsets in
     * [base] ± [searched], POV 2 lying in [pov2Start, pov2End). The envelopes must cover what is compared.
     *
     * @return null if there is too little of POV 2 to compare
     */
    fun matchWindow(pov1: AudioEnvelope, pov2: AudioEnvelope, from: Long, window: Long, base: Long, searched: Long, pov2Start: Long, pov2End: Long): AudioOffsetMatcher.Match? {
        val start = snap(from)
        val snappedBase = snap(base)
        val snappedSearched = snap(searched)
        val pov2From = maxOf(start + snappedBase - snappedSearched, ceil(pov2Start), pov2.startMs)
        val pov2To = minOf(start + snappedBase + window + snappedSearched, snap(pov2End), pov2.endMs)
        if (pov2To - pov2From < window + MIN_SEARCH) return null
        val windows = listOf(AudioOffsetMatcher.Window(pov1.slice(start, start + window), pov2.slice(pov2From, pov2To)))
        return AudioOffsetMatcher.match(windows, snappedBase, snappedSearched)
    }

    /**
     * Search range around [base] that reaches all of POV 2 ([pov2Start, pov2End)) from the POV 1
     * stretch [from, from + window)
     */
    fun wholeRange(from: Long, window: Long, base: Long, pov2Start: Long, pov2End: Long) =
            ceil(max(abs(from + base - pov2Start), abs(pov2End - (from + base + window))))

    /**
     * Where, between the start of [pov1] and its end, [oldOffset] gives way to [newOffset]: the
     * moment that best splits the sounds of [pov1] into those [oldOffset] matches (before) and
     * those [newOffset] matches (after). [pov2] has to cover both offsets' matching stretches.
     */
    fun findCut(pov1: AudioEnvelope, pov2: AudioEnvelope, oldOffset: Long, newOffset: Long): Long {
        val x = pov1.onsets()
        val y = pov2.onsets()
        val hop = AudioEnvelope.HOP_MS
        val oldShift = ((pov1.startMs + oldOffset - pov2.startMs).toDouble() / hop).roundToInt()
        val newShift = ((pov1.startMs + newOffset - pov2.startMs).toDouble() / hop).roundToInt()
        // Each microphone hears a sound at its own time: allow a step either way
        fun near(index: Int): Float {
            var best = 0F
            for (j in index - 1..index + 1) if (j in y.indices && y[j] > best) best = y[j]
            return best
        }
        var sum = 0.0
        var bestSum = 0.0
        var best = 0
        for (i in x.indices) {
            sum += x[i] * (near(i + oldShift) - near(i + newShift))
            if (sum > bestSum) {
                bestSum = sum
                best = i + 1
            }
        }
        return pov1.startMs + best * hop
    }

    /**
     * Groups consecutive accepted [matches] agreeing within [tolerance] into runs. A run of one
     * window between two runs that agree with each other is taken for a false match and dropped.
     */
    fun runs(matches: List<WindowMatch>, tolerance: Long = RUN_TOLERANCE): List<Run> {
        val groups = ArrayList<MutableList<Int>>()
        for ((index, match) in matches.withIndex()) {
            val offset = match.offset ?: continue
            val group = groups.lastOrNull()
            if (group != null && abs(median(group.map { matches[it].offset!! }) - offset) <= tolerance) group.add(index)
            else groups.add(mutableListOf(index))
        }
        fun offsetOf(group: List<Int>) = median(group.map { matches[it].offset!! })
        // Drop lone outliers, then join the groups they separated
        val kept = groups.filterIndexed { i, group ->
            val previous = groups.getOrNull(i - 1)
            val next = groups.getOrNull(i + 1)
            !(group.size == 1 && previous != null && next != null && abs(offsetOf(previous) - offsetOf(next)) <= tolerance)
        }
        val joined = ArrayList<MutableList<Int>>()
        for (group in kept) {
            val last = joined.lastOrNull()
            if (last != null && abs(offsetOf(last) - offsetOf(group)) <= tolerance) last.addAll(group)
            else joined.add(group.toMutableList())
        }
        return joined.map { Run(it.first(), it.last(), offsetOf(it)) }
    }

    /**
     * Finds the segments of a whole video: [pov1] covers POV 1 ([start, split)), [pov2] covers POV 2
     * ([split, end)). Windows of [SCAN_WINDOW] every [SCAN_STEP] are matched near the previous
     * window's offset (starting with [initialOffset]), then across all of POV 2 if that fails.
     *
     * @param onProgress the fraction of windows matched
     * @return null if no window matched
     */
    fun scan(pov1: AudioEnvelope, pov2: AudioEnvelope, start: Long, split: Long, end: Long, initialOffset: Long,
             onProgress: (Float) -> Unit = {}, isActive: () -> Boolean = { true }): List<Segment>? {
        val window = minOf(SCAN_WINDOW, snap(split - start))
        if (window < MIN_WINDOW) return null
        val starts = ArrayList<Long>()
        var from = ceil(start)
        while (from + window <= split) {
            starts.add(from)
            from += SCAN_STEP
        }
        if (starts.isEmpty()) return null
        val matches = ArrayList<WindowMatch>(starts.size)
        var base = initialOffset
        for ((index, windowStart) in starts.withIndex()) {
            if (!isActive()) return null
            var match = matchWindow(pov1, pov2, windowStart, window, base, LOCAL_RANGE, split, end)?.takeIf { it.accepted }
            if (match == null) {
                val range = wholeRange(windowStart, window, base, split, end)
                match = matchWindow(pov1, pov2, windowStart, window, base, range, split, end)?.takeIf { it.accepted }
            }
            matches.add(WindowMatch(windowStart, match?.offsetMs))
            if (match != null) base = match.offsetMs
            onProgress((index + 1F) / starts.size)
        }
        val runs = runs(matches)
        if (runs.isEmpty()) return null
        val segments = arrayListOf(Segment(start, runs[0].offset))
        for ((previous, next) in runs.zipWithNext()) {
            // The cut lies between the last window of one run and the first of the next
            val searchFrom = matches[previous.last].pov1Time
            val searchTo = matches[next.first].pov1Time + window
            val cut = findCut(pov1.slice(searchFrom, searchTo), pov2, previous.offset, next.offset)
            segments.add(Segment(cut, next.offset))
        }
        return segments
    }

    private const val MIN_WINDOW = 3_000L

    private fun median(values: List<Long>) = values.sorted()[values.size / 2]

    fun snap(time: Long) = time.floorDiv(AudioEnvelope.HOP_MS) * AudioEnvelope.HOP_MS

    fun ceil(time: Long) = snap(time + AudioEnvelope.HOP_MS - 1)
}
