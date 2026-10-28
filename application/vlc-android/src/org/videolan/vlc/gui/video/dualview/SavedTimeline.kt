/*
 * ************************************************************************
 *  SavedTimeline.kt
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
 * A video's timeline and audible POV, as last used
 */
data class SavedTimeline(val timeline: DualViewTimeline, val audioPov: Int)

/**
 * Saved timelines as strings. Version 2 is
 * `v2;length;start;split;end;audioPov;usedAt;from:offset,from:offset...`;
 * version 1, `split;offset;audioPov;length;usedAt`, had a single offset over the whole file.
 */
object SavedTimelineCodec {
    private const val VERSION = "v2"
    private const val LENGTH_TOLERANCE = 1_000L

    fun encode(saved: SavedTimeline, usedAt: Long): String {
        val timeline = saved.timeline
        val segments = timeline.segments.joinToString(",") { "${it.from}:${it.offset}" }
        return "$VERSION;${timeline.length};${timeline.start};${timeline.split};${timeline.end};${saved.audioPov};$usedAt;$segments"
    }

    /**
     * @param length the video's length now: a timeline saved for another length is dropped
     */
    fun decode(value: String, length: Long): SavedTimeline? = try {
        val fields = value.split(';')
        val saved = if (fields[0] == VERSION) {
            val segments = fields[7].split(',').map { segment -> segment.split(':').let { Segment(it[0].toLong(), it[1].toLong()) } }
            val savedLength = fields[1].toLong()
            if (kotlin.math.abs(savedLength - length) > LENGTH_TOLERANCE) null
            else {
                val start = fields[2].toLong()
                val split = fields[3].toLong()
                val end = minOf(fields[4].toLong(), length)
                if (start < 0 || !DualViewTimeline.canBound(start, split, end)) null
                else SavedTimeline(DualViewTimeline(length, start, split, end, DualViewTimeline.fit(segments, start, split)), fields[5].toInt())
            }
        } else {
            val values = fields.map { it.toLong() }
            val split = values[0]
            if (kotlin.math.abs(values[3] - length) > LENGTH_TOLERANCE || !DualViewTimeline.canSplit(length, split)) null
            else SavedTimeline(DualViewTimeline.of(length, split, values[1]), values[2].toInt())
        }
        saved?.takeIf { it.audioPov == 1 || it.audioPov == 2 }
    } catch (e: RuntimeException) {
        // Malformed: NumberFormatException, IndexOutOfBoundsException, IllegalArgumentException
        null
    }

    fun usedAt(value: String): Long? {
        val fields = value.split(';')
        return if (fields[0] == VERSION) fields.getOrNull(6)?.toLongOrNull() else fields.getOrNull(4)?.toLongOrNull()
    }
}
