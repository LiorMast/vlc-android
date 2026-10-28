/*
 * ************************************************************************
 *  DualViewTimeline.kt
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

package org.videolan.vlc.gui.video.dualview

/**
 * From POV 1 time [from] on, the moment shown at t in POV 1 is shown at t + [offset] in POV 2
 */
data class Segment(val from: Long, val offset: Long)

/**
 * A video made of two recordings of the same event stitched one after the other:
 * POV 1 is [[start], [split]) and POV 2 is [[split], [end]); what lies outside belongs to neither.
 *
 * The recordings may have been cut, which changes how far apart matching moments are partway:
 * [segments] holds the offset from each POV 1 moment on, the first one starting at [start].
 * All times are in ms.
 */
data class DualViewTimeline(val length: Long, val start: Long, val split: Long, val end: Long, val segments: List<Segment>) {

    init {
        require(start >= 0 && end <= length && canBound(start, split, end)) { "Invalid bounds $start, $split, $end for a length of $length" }
        require(segments.isNotEmpty() && segments[0].from == start && segments.last().from < split) { "Invalid segments $segments" }
        require(segments.zipWithNext().all { (a, b) -> a.from < b.from }) { "Unsorted segments $segments" }
    }

    /**
     * The POV (1 or 2) the video shows at [time], 0 outside both
     */
    fun povAt(time: Long) = when {
        time < start || time >= end -> 0
        time < split -> 1
        else -> 2
    }

    fun startOf(pov: Int) = if (pov == 1) start else split

    /**
     * Last time of [pov] that is safe to show: it stays [EDGE_MARGIN] away from the end of its footage
     */
    fun lastOf(pov: Int) = (if (pov == 1) split else end) - EDGE_MARGIN

    /**
     * Index of the segment holding [pov1Time], clamped to the existing ones
     */
    fun segmentIndexAt(pov1Time: Long): Int {
        var index = 0
        while (index < segments.size - 1 && segments[index + 1].from <= pov1Time) index++
        return index
    }

    fun offsetAt(pov1Time: Long) = segments[segmentIndexAt(pov1Time)].offset

    /**
     * Where the segment at [index] ends in POV 1
     */
    fun segmentEnd(index: Int) = if (index < segments.size - 1) segments[index + 1].from else split

    /**
     * The POV 1 moment matching [time]: itself in POV 1, its counterpart in POV 2
     */
    fun pov1TimeAt(time: Long) = if (povAt(time) == 2) counterpart(time).time else time.coerceIn(start, split - 1)

    /**
     * The moment matching [time] in the other POV, clamped to the footage that POV has
     */
    fun counterpart(time: Long): Counterpart = when (povAt(time)) {
        1 -> {
            val index = segmentIndexAt(time)
            val target = time + segments[index].offset
            when {
                target < split -> Counterpart(2, split, CounterpartState.NOT_STARTED, index)
                target > lastOf(2) -> Counterpart(2, lastOf(2), CounterpartState.ENDED, index)
                else -> Counterpart(2, target, CounterpartState.PLAYING, index)
            }
        }
        2 -> pov1Counterpart(time)
        else -> if (time < start) Counterpart(2, split, CounterpartState.OUTSIDE, 0)
        else Counterpart(1, lastOf(1), CounterpartState.OUTSIDE, segments.size - 1)
    }

    /**
     * The POV 1 moment matching [time] of POV 2. With cuts, a POV 2 moment can match none (POV 1
     * lost that footage) or several (POV 2 lost some): the latest segment wins.
     */
    private fun pov1Counterpart(time: Long): Counterpart {
        for (index in segments.indices.reversed()) {
            val target = time - segments[index].offset
            if (target >= segments[index].from && target < segmentEnd(index)) {
                return if (target > lastOf(1)) Counterpart(1, lastOf(1), CounterpartState.ENDED, index)
                else Counterpart(1, target, CounterpartState.PLAYING, index)
            }
        }
        // No footage: show the nearest POV 1 moment there is
        var nearest = 0
        var distance = Long.MAX_VALUE
        var before = 0
        var after = 0
        for (index in segments.indices) {
            val first = segments[index].from + segments[index].offset
            val last = segmentEnd(index) + segments[index].offset
            val gap = if (time < first) first - time else time - last
            if (time < first) before++ else after++
            if (gap < distance) {
                distance = gap
                nearest = index
            }
        }
        val target = (time - segments[nearest].offset).coerceIn(segments[nearest].from, segmentEnd(nearest) - 1).coerceIn(start, lastOf(1))
        val state = when {
            after == 0 -> CounterpartState.NOT_STARTED
            before == 0 -> CounterpartState.ENDED
            else -> CounterpartState.GAP
        }
        return Counterpart(1, target, state, nearest)
    }

    /**
     * Sets the offset of the segment holding [pov1Time]
     */
    fun withOffsetAt(pov1Time: Long, offset: Long): DualViewTimeline {
        val index = segmentIndexAt(pov1Time)
        return copy(segments = segments.toMutableList().apply { set(index, Segment(segments[index].from, offset)) })
    }

    /**
     * Starts a new segment with [offset] at [at], replacing one starting less than
     * [MIN_SEGMENT] away. The first segment can't be replaced: its offset changes instead.
     */
    fun withCut(at: Long, offset: Long): DualViewTimeline {
        if (at - start < MIN_SEGMENT) return withOffsetAt(start, offset)
        if (split - at < MIN_SEGMENT) return this
        val kept = segments.filter { it.from == start || kotlin.math.abs(it.from - at) >= MIN_SEGMENT }
        return copy(segments = (kept + Segment(at, offset)).sortedBy { it.from })
    }

    /**
     * Removes the cut starting the segment at [index]: the previous segment's offset goes on
     */
    fun withoutCut(index: Int): DualViewTimeline {
        if (index <= 0 || index >= segments.size) return this
        return copy(segments = segments.filterIndexed { i, _ -> i != index })
    }

    /**
     * Moves the cut starting the segment at [index] to [at], staying between its neighbours
     */
    fun withCutMoved(index: Int, at: Long): DualViewTimeline {
        if (index <= 0 || index >= segments.size) return this
        val low = segments[index - 1].from + MIN_SEGMENT
        val high = segmentEnd(index) - MIN_SEGMENT
        if (high < low) return this
        return copy(segments = segments.toMutableList().apply { set(index, Segment(at.coerceIn(low, high), segments[index].offset)) })
    }

    /**
     * Replaces all the segments: they are sorted and fitted between [start] and [split]
     */
    fun withSegments(segments: List<Segment>) = copy(segments = fit(segments, start, split))

    /**
     * Moves the bounds of the POVs, the matching moments staying the same
     */
    fun withBounds(start: Long, split: Long, end: Long): DualViewTimeline {
        require(canBound(start, split, end) && start >= 0 && end <= length)
        return DualViewTimeline(length, start, split, end, fit(segments, start, split))
    }

    companion object {
        const val EDGE_MARGIN = 300L
        const val MIN_POV_LENGTH = 5_000L
        const val MIN_SEGMENT = 1_000L

        /**
         * A timeline of the whole file, without cuts
         */
        fun of(length: Long, split: Long, offset: Long) = DualViewTimeline(length, 0L, split, length, listOf(Segment(0L, offset)))

        /**
         * Whether both POVs would be at least [MIN_POV_LENGTH] long
         */
        fun canBound(start: Long, split: Long, end: Long) = split - start >= MIN_POV_LENGTH && end - split >= MIN_POV_LENGTH

        fun canSplit(length: Long, split: Long) = canBound(0L, split, length)

        /**
         * [segments] sorted, without duplicates, and covering [start, split): the last one starting
         * at or before [start] now starts there, and those starting too close to [split] go
         */
        fun fit(segments: List<Segment>, start: Long, split: Long): List<Segment> {
            val sorted = segments.sortedBy { it.from }.distinctBy { it.from }
            if (sorted.isEmpty()) return listOf(Segment(start, split - start))
            val first = sorted.lastOrNull { it.from <= start } ?: sorted.first()
            val rest = sorted.filter { it.from != first.from && it.from - start >= MIN_SEGMENT && split - it.from >= MIN_SEGMENT }
            return listOf(Segment(start, first.offset)) + rest
        }
    }
}

enum class CounterpartState {
    NOT_STARTED, PLAYING, ENDED,

    /**
     * The other POV lost this stretch of footage to a cut
     */
    GAP,

    /**
     * The moment belongs to neither POV
     */
    OUTSIDE
}

/**
 * A moment of [pov] at [time]. When [state] isn't [CounterpartState.PLAYING], the POV has no footage
 * for that moment and [time] is the nearest one it has. [segment] is the timeline segment the
 * matching was done with.
 */
data class Counterpart(val pov: Int, val time: Long, val state: CounterpartState, val segment: Int = 0)
