/*
 * ************************************************************************
 *  AudioOffsetMatcher.kt
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds how far apart the same sounds are in the two POVs of a video.
 *
 * It correlates the onsets of both POVs' audio with GCC-PHAT, the phase transform used to
 * time sounds between microphones: it weighs every rhythm of the onsets alike, so a steady beat
 * (music, chants) can't drown the one-off sounds that pin down the offset, and those give a sharp
 * peak.
 *
 * Two cameras never hear exactly the same mix: each sound reaches each microphone after its own
 * travel time (3 ms per metre), so the offset found is the one of the sounds both heard best,
 * and can differ from the pictures' by a few tens of ms.
 */
object AudioOffsetMatcher {

    /**
     * Audio of POV 1 ([pov1]) and the stretch of POV 2 to look for it in ([pov2])
     */
    class Window(val pov1: AudioEnvelope, val pov2: AudioEnvelope)

    /**
     * @param offsetMs the moment at t in POV 1 is heard at t + offsetMs in POV 2
     * @param score how far the correlation peaks there, in standard deviations of the
     * correlation over the offsets searched
     * @param runnerUp highest score more than [RUNNER_UP_GAP_MS] away from it
     */
    data class Match(val offsetMs: Long, val score: Float, val runnerUp: Float) {
        val accepted: Boolean
            get() = score >= MIN_SCORE && (runnerUp <= 0F || score >= MIN_RATIO * runnerUp)
    }

    const val MIN_SCORE = 6F
    const val MIN_RATIO = 2F
    const val RUNNER_UP_GAP_MS = 250L
    // Phase transform strength: 1 weighs every frequency alike, less keeps some of the louder ones' weight
    private const val PHAT_EXPONENT = 0.7

    /**
     * Searches offsets in [baseOffsetMs] ± [rangeMs], averaging the correlation of all [windows].
     * [baseOffsetMs] and the envelopes' starts must lie on the [AudioEnvelope.HOP_MS] grid.
     *
     * @return null if no offset in range could be compared
     */
    fun match(windows: List<Window>, baseOffsetMs: Long, rangeMs: Long): Match? {
        val hop = AudioEnvelope.HOP_MS
        val lags = (rangeMs / hop).toInt()
        val size = 2 * lags + 1
        val sums = DoubleArray(size)
        val counts = IntArray(size)
        for (window in windows) {
            val curve = correlate(window, baseOffsetMs, lags) ?: continue
            for (i in 0 until size) if (!curve[i].isNaN()) {
                sums[i] += curve[i]
                counts[i]++
            }
        }
        val coverage = counts.maxOrNull() ?: 0
        if (coverage == 0) return null
        // Windows near the ends of the file can't test every offset: compare those most of them could
        val required = (coverage + 1) / 2
        val scores = DoubleArray(size) { if (counts[it] >= required) sums[it] / counts[it] else Double.NaN }
        var best = -1
        for (i in 0 until size) if (!scores[i].isNaN() && (best < 0 || scores[i] > scores[best])) best = i
        if (best < 0) return null
        val gap = (RUNNER_UP_GAP_MS / hop).toInt()
        var runnerUp = Double.NEGATIVE_INFINITY
        for (i in 0 until size) if (abs(i - best) > gap && !scores[i].isNaN() && scores[i] > runnerUp) runnerUp = scores[i]
        // Parabolic interpolation between the steps around the peak
        var fraction = 0.0
        if (best > 0 && best < size - 1 && !scores[best - 1].isNaN() && !scores[best + 1].isNaN()) {
            val left = scores[best - 1]
            val right = scores[best + 1]
            val curvature = left - 2 * scores[best] + right
            if (curvature < 0.0) fraction = (0.5 * (left - right) / curvature).coerceIn(-0.5, 0.5)
        }
        val offset = baseOffsetMs + ((best - lags + fraction) * hop).toLong()
        return Match(offset, scores[best].toFloat(), if (runnerUp == Double.NEGATIVE_INFINITY) 0F else runnerUp.toFloat())
    }

    /**
     * GCC-PHAT correlation of [window]'s POV 1 onsets with its POV 2 ones, for each offset
     * [baseOffsetMs] + lag × [AudioEnvelope.HOP_MS], lag in ±[lags], standardized over the offsets it
     * could test (the others hold NaN)
     */
    private fun correlate(window: Window, baseOffsetMs: Long, lags: Int): DoubleArray? {
        val hop = AudioEnvelope.HOP_MS
        val x = window.pov1.onsets()
        val y = window.pov2.onsets()
        val n = x.size
        if (n == 0) return null
        // Index in y of the POV 2 step matching the first POV 1 step, at the base offset
        val base = ((window.pov1.startMs + baseOffsetMs - window.pov2.startMs) / hop).toInt()
        val from = maxOf(base - lags, 0)
        val to = minOf(base + lags + n, y.size)
        if (to - from < n) return null
        // Room for every shift without wrapping around
        var fftSize = 1
        while (fftSize < to - from + n) fftSize = fftSize shl 1
        val xRe = DoubleArray(fftSize)
        val xIm = DoubleArray(fftSize)
        val yRe = DoubleArray(fftSize)
        val yIm = DoubleArray(fftSize)
        val xMean = x.average()
        for (i in 0 until n) xRe[i] = x[i] - xMean
        var yMean = 0.0
        for (i in from until to) yMean += y[i]
        yMean /= to - from
        for (i in from until to) yRe[i - from] = y[i] - yMean
        fft(xRe, xIm, false)
        fft(yRe, yIm, false)
        for (f in 0 until fftSize) {
            // conj(X) × Y, whitened
            val re = xRe[f] * yRe[f] + xIm[f] * yIm[f]
            val im = xRe[f] * yIm[f] - xIm[f] * yRe[f]
            val weight = sqrt(re * re + im * im).pow(PHAT_EXPONENT) + 1e-12
            xRe[f] = re / weight
            xIm[f] = im / weight
        }
        fft(xRe, xIm, true)
        // xRe[k]: correlation with the POV 2 stretch starting k steps after `from`
        val curve = DoubleArray(2 * lags + 1) { Double.NaN }
        var sum = 0.0
        var squares = 0.0
        var valid = 0
        for (lag in -lags..lags) {
            val k = base + lag - from
            if (k < 0 || k + n > to - from) continue
            val value = xRe[k]
            curve[lag + lags] = value
            sum += value
            squares += value * value
            valid++
        }
        if (valid < 2) return null
        val mean = sum / valid
        val deviation = sqrt((squares / valid - mean * mean).coerceAtLeast(0.0))
        if (deviation <= 1e-12) return null
        for (i in curve.indices) if (!curve[i].isNaN()) curve[i] = (curve[i] - mean) / deviation
        return curve
    }

    /**
     * In-place radix-2 FFT; [re] and [im] sizes must be the same power of 2
     */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var length = 2
        while (length <= n) {
            val angle = 2 * PI / length * if (inverse) 1 else -1
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            val half = length / 2
            var start = 0
            while (start < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until half) {
                    val a = start + k
                    val b = a + half
                    val bRe = re[b] * wRe - im[b] * wIm
                    val bIm = re[b] * wIm + im[b] * wRe
                    re[b] = re[a] - bRe
                    im[b] = im[a] - bIm
                    re[a] += bRe
                    im[a] += bIm
                    val next = wRe * stepRe - wIm * stepIm
                    wIm = wRe * stepIm + wIm * stepRe
                    wRe = next
                }
                start += length
            }
            length = length shl 1
        }
        if (inverse) for (i in 0 until n) {
            re[i] /= n
            im[i] /= n
        }
    }
}
