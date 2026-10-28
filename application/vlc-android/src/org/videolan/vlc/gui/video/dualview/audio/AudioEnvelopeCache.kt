/*
 * ************************************************************************
 *  AudioEnvelopeCache.kt
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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The audio of one video read so far, shared by audio sync, the cut scan and the waveform strips:
 * each stretch is only read once.
 */
class AudioEnvelopeCache(private val source: AudioEnvelopeSource) {

    private val reading = Mutex()
    // Sorted, apart from each other, on the step grid; guarded by itself
    private val parts = ArrayList<AudioEnvelope>()

    /**
     * Envelopes of [ranges] ([first, last) in ms, widened to the step grid), reading what is missing
     */
    suspend fun get(ranges: List<LongRange>, progress: (Float) -> Unit = {}): List<AudioEnvelope> = reading.withLock {
        val wanted = ranges.map { snap(it.first)..ceil(it.last) }
        val missing = merge(wanted).flatMap { missing(it) }
        if (missing.isNotEmpty()) {
            val read = source.read(missing, progress)
            synchronized(parts) { read.forEach { add(it) } }
        }
        wanted.map { range -> envelope(range) }
    }

    /**
     * Fills [out] with the energy of the [out].size steps from [fromMs] (on the step grid), NaN
     * where nothing was read. Doesn't wait for reads in progress.
     *
     * @return whether every step was read
     */
    fun peek(fromMs: Long, out: FloatArray): Boolean {
        out.fill(Float.NaN)
        var complete = true
        synchronized(parts) {
            val toMs = fromMs + out.size * AudioEnvelope.HOP_MS
            var covered = 0L
            for (part in parts) {
                if (part.endMs <= fromMs || part.startMs >= toMs) continue
                val from = maxOf(part.startMs, fromMs)
                val to = minOf(part.endMs, toMs)
                part.energy.copyInto(out, ((from - fromMs) / AudioEnvelope.HOP_MS).toInt(),
                        ((from - part.startMs) / AudioEnvelope.HOP_MS).toInt(), ((to - part.startMs) / AudioEnvelope.HOP_MS).toInt())
                covered += to - from
            }
            if (covered < toMs - fromMs) complete = false
        }
        return complete
    }

    /**
     * Whether all of [range] was read
     */
    fun has(range: LongRange) = synchronized(parts) { missing(snap(range.first)..ceil(range.last)).isEmpty() }

    /**
     * The parts of [range] no part covers
     */
    private fun missing(range: LongRange): List<LongRange> = synchronized(parts) {
        val result = ArrayList<LongRange>()
        var from = range.first
        for (part in parts) {
            if (part.endMs <= from) continue
            if (part.startMs >= range.last) break
            if (part.startMs > from) result.add(from..part.startMs)
            from = maxOf(from, part.endMs)
        }
        if (from < range.last) result.add(from..range.last)
        result
    }

    /**
     * Inserts [envelope], joining it with the parts it touches. It only holds steps no part has.
     */
    private fun add(envelope: AudioEnvelope) {
        if (envelope.energy.isEmpty()) return
        var index = parts.indexOfFirst { it.startMs > envelope.startMs }.let { if (it < 0) parts.size else it }
        parts.add(index, envelope)
        // Join with the next part, then with the previous one
        if (index + 1 < parts.size && parts[index].endMs == parts[index + 1].startMs) {
            parts[index] = join(parts[index], parts[index + 1])
            parts.removeAt(index + 1)
        }
        if (index > 0 && parts[index - 1].endMs == parts[index].startMs) {
            parts[index - 1] = join(parts[index - 1], parts[index])
            parts.removeAt(index)
            index--
        }
    }

    private fun join(first: AudioEnvelope, second: AudioEnvelope) = AudioEnvelope(first.startMs, first.energy + second.energy)

    /**
     * [range], assembled from the parts (NaN where missing)
     */
    private fun envelope(range: LongRange): AudioEnvelope {
        val energy = FloatArray(((range.last - range.first) / AudioEnvelope.HOP_MS).toInt())
        peek(range.first, energy)
        return AudioEnvelope(range.first, energy)
    }

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
}
