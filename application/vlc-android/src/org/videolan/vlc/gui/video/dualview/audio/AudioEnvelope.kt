/*
 * ************************************************************************
 *  AudioEnvelope.kt
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

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Loudness of a stretch of audio: the mean square of its samples for each [HOP_MS] step,
 * the first step starting at [startMs]. A step no sample fell in holds NaN.
 */
class AudioEnvelope(val startMs: Long, val energy: FloatArray) {

    val endMs: Long
        get() = startMs + energy.size * HOP_MS

    /**
     * The steps covering [fromMs, toMs), both rounded down to the step grid
     */
    fun slice(fromMs: Long, toMs: Long): AudioEnvelope {
        val from = ((fromMs - startMs) / HOP_MS).toInt().coerceIn(0, energy.size)
        val to = ((toMs - startMs) / HOP_MS).toInt().coerceIn(from, energy.size)
        return AudioEnvelope(startMs + from * HOP_MS, energy.copyOfRange(from, to))
    }

    /**
     * How sharply the loudness rises at each step (positive log-energy differences, lightly
     * smoothed). Two microphones recording the same event agree on it even when their levels and
     * tones differ.
     */
    fun onsets(): FloatArray {
        val size = energy.size
        val log = FloatArray(size)
        var last = SILENCE
        for (i in 0 until size) {
            val e = energy[i]
            if (!e.isNaN()) last = ln(e + ENERGY_FLOOR)
            log[i] = last
        }
        val rises = FloatArray(size)
        for (i in 1 until size) rises[i] = max(0F, log[i] - log[i - 1])
        val smoothed = FloatArray(size)
        for (i in 0 until size) {
            val previous = if (i > 0) rises[i - 1] else rises[i]
            val next = if (i < size - 1) rises[i + 1] else rises[i]
            smoothed[i] = 0.25F * previous + 0.5F * rises[i] + 0.25F * next
        }
        return smoothed
    }

    companion object {
        const val HOP_MS = 10L
        private const val ENERGY_FLOOR = 1e-10F
        private val SILENCE = ln(ENERGY_FLOOR)
    }
}

/**
 * Builds the [AudioEnvelope] of [fromMs, toMs) from samples placed by their timestamps.
 *
 * Samples first go through a high-pass filter at [highPassHz] (0 for none): wind, rumble and bass
 * beats would otherwise dominate the loudness, while the sharp sounds both microphones share
 * (impacts, voices, claps) sit higher.
 *
 * Only one sample in every few is used, about [PROCESSED_RATE] of them per second: a 10 ms step
 * still gets over a hundred, and the higher frequencies that skipping folds down still belong to
 * the same sounds. Samples are processed in runs falling in the same step, with the filter's state
 * in locals: this stays fast in debuggable builds too, where per-sample work is slow.
 */
class EnvelopeBuilder(private val fromMs: Long, toMs: Long, private val highPassHz: Float = HIGH_PASS_HZ) {
    private val steps = ((toMs - fromMs) / AudioEnvelope.HOP_MS).toInt().coerceAtLeast(0)
    private val sums = DoubleArray(steps)
    private val counts = IntArray(steps)

    // Two cascaded second-order sections with the same coefficients, designed for filterRate
    private var filterRate = 0
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private val state = DoubleArray(8)
    // Use one sample in every `stride`, the next one in `skip` samples
    private var stride = 1
    private var skip = 0

    /**
     * Adds [count] mono samples, the first one played at [firstUs] (µs)
     */
    fun add(firstUs: Long, sampleRate: Int, samples: FloatArray, count: Int) = addSamples(firstUs, sampleRate, count) { samples[it] }

    /**
     * Adds [frames] frames of interleaved 16-bit samples, mixed down to mono
     */
    fun add16(firstUs: Long, sampleRate: Int, samples: ShortArray, frames: Int, channels: Int) {
        val scale = 1F / (32768F * channels)
        addSamples(firstUs, sampleRate, frames) { frame ->
            var mixed = 0
            var index = frame * channels
            for (c in 0 until channels) mixed += samples[index++]
            mixed * scale
        }
    }

    /**
     * Adds [frames] frames of interleaved float samples, mixed down to mono
     */
    fun addFloat(firstUs: Long, sampleRate: Int, samples: FloatArray, frames: Int, channels: Int) {
        val scale = 1F / channels
        addSamples(firstUs, sampleRate, frames) { frame ->
            var mixed = 0F
            var index = frame * channels
            for (c in 0 until channels) mixed += samples[index++]
            mixed * scale
        }
    }

    /**
     * Adds [count] consecutive samples, [sample] giving each one, split into runs falling in the
     * same step
     */
    private inline fun addSamples(firstUs: Long, sampleRate: Int, count: Int, sample: (Int) -> Float) {
        if (sampleRate <= 0) return
        if (sampleRate != filterRate) design(sampleRate)
        val filtering = highPassHz > 0F
        val stride = stride
        var skip = skip
        var x1 = state[0]; var x2 = state[1]; var y1 = state[2]; var y2 = state[3]
        var u1 = state[4]; var u2 = state[5]; var z1 = state[6]; var z2 = state[7]
        val b0 = b0; val b1 = b1; val b2 = b2; val a1 = a1; val a2 = a2
        val stepUs = AudioEnvelope.HOP_MS * 1000
        val startUs = fromMs * 1000
        var i = 0
        while (i < count) {
            val step = (firstUs + i * 1_000_000L / sampleRate - startUs).floorDiv(stepUs)
            if (step >= steps) break
            // First sample played at or after the start of the next step
            val boundaryUs = startUs + (step + 1) * stepUs
            val next = (((boundaryUs - firstUs) * sampleRate + 999_999) / 1_000_000).toInt().coerceIn(i + 1, count)
            var sum = 0.0
            var used = 0
            for (j in i until next) {
                if (skip > 0) {
                    skip--
                    continue
                }
                skip = stride - 1
                used++
                var value = sample(j).toDouble()
                if (filtering) {
                    val y = b0 * value + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                    x2 = x1; x1 = value; y2 = y1; y1 = y
                    val z = b0 * y + b1 * u1 + b2 * u2 - a1 * z1 - a2 * z2
                    u2 = u1; u1 = y; z2 = z1; z1 = z
                    value = z
                }
                sum += value * value
            }
            if (step >= 0) {
                sums[step.toInt()] += sum
                counts[step.toInt()] += used
            }
            i = next
        }
        state[0] = x1; state[1] = x2; state[2] = y1; state[3] = y2
        state[4] = u1; state[5] = u2; state[6] = z1; state[7] = z2
        this.skip = skip
    }

    /**
     * Butterworth-like high-pass section (RBJ cookbook, Q = 1/√2) for [sampleRate]
     */
    private fun design(sampleRate: Int) {
        filterRate = sampleRate
        stride = (sampleRate / PROCESSED_RATE).coerceAtLeast(1)
        skip = 0
        state.fill(0.0)
        if (highPassHz <= 0F) return
        val w = 2 * PI * highPassHz * stride / sampleRate
        val alpha = sin(w) / (2 * Q)
        val c = cos(w)
        val a0 = 1 + alpha
        b0 = (1 + c) / 2 / a0
        b1 = -(1 + c) / a0
        b2 = b0
        a1 = -2 * c / a0
        a2 = (1 - alpha) / a0
    }

    fun build() = AudioEnvelope(fromMs, FloatArray(steps) { if (counts[it] == 0) Float.NaN else (sums[it] / counts[it]).toFloat() })

    companion object {
        const val HIGH_PASS_HZ = 500F
        const val PROCESSED_RATE = 12_000
        private const val Q = 0.7071
    }
}
