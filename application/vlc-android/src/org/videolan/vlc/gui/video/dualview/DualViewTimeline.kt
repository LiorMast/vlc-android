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
 * A video made of two recordings of the same event stitched one after the other:
 * POV 1 is [0, [split]) and POV 2 is [[split], [length]).
 * The moment shown at time t of POV 1 is shown at t + [offset] in POV 2.
 * All times are in ms.
 */
data class DualViewTimeline(val length: Long, val split: Long, val offset: Long) {

    init {
        require(canSplit(length, split)) { "Invalid split $split for a length of $length" }
    }

    /**
     * How far the matching moments are from a plain cut: 0 when both POVs started recording together
     */
    val shift: Long
        get() = offset - split

    /**
     * The POV (1 or 2) the video shows at [time]
     */
    fun povAt(time: Long) = if (time < split) 1 else 2

    fun startOf(pov: Int) = if (pov == 1) 0L else split

    /**
     * Last time of [pov] that is safe to show: it stays [EDGE_MARGIN] away from the end of its footage
     */
    fun lastOf(pov: Int) = (if (pov == 1) split else length) - EDGE_MARGIN

    /**
     * The moment matching [time] in the other POV, clamped to the footage that POV has
     */
    fun counterpart(time: Long): Counterpart {
        val pov = povAt(time)
        val other = 3 - pov
        val target = if (pov == 1) time + offset else time - offset
        return when {
            target < startOf(other) -> Counterpart(other, startOf(other), CounterpartState.NOT_STARTED)
            target > lastOf(other) -> Counterpart(other, lastOf(other), CounterpartState.ENDED)
            else -> Counterpart(other, target, CounterpartState.PLAYING)
        }
    }

    fun withOffset(offset: Long) = copy(offset = offset)

    companion object {
        const val EDGE_MARGIN = 300L
        const val MIN_POV_LENGTH = 5_000L

        /**
         * Whether both POVs would be at least [MIN_POV_LENGTH] long
         */
        fun canSplit(length: Long, split: Long) = split >= MIN_POV_LENGTH && length - split >= MIN_POV_LENGTH
    }
}

enum class CounterpartState { NOT_STARTED, PLAYING, ENDED }

/**
 * A moment of [pov] at [time]. When [state] isn't [CounterpartState.PLAYING], the POV has no footage
 * for that moment and [time] is the nearest one it has
 */
data class Counterpart(val pov: Int, val time: Long, val state: CounterpartState)
