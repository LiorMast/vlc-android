/*
 * ************************************************************************
 *  DualViewWaveformView.kt
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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.gui.video.dualview.audio.AudioEnvelope
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Lines up the two POVs by ear and eye: a strip of each POV's audio around the current moment,
 * POV 1 above and POV 2 below, the moments the timeline matches under the centre line.
 *
 * Drag a strip sideways until its peaks sit under the other's: that changes the offset of the
 * current segment, live, so the window follows. Pinch to zoom. The overlay mode draws both on one
 * strip. The match figure tells how much the visible sounds agree.
 */
class DualViewWaveformView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var delegate: DualViewDelegate? = null

    /**
     * Both POVs on a single strip
     */
    var overlay = false
        set(value) {
            field = value
            invalidate()
        }

    private val pov1Paint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.orange500)
        strokeWidth = 1F
    }
    private val pov2Paint = Paint().apply {
        color = POV2_COLOR
        strokeWidth = 1F
    }
    private val missingPaint = Paint().apply { color = 0x33FFFFFF }
    private val stripPaint = Paint().apply { color = 0x66000000 }
    private val playheadPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 2.dp.toFloat()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12.dp.toFloat()
        setShadowLayer(2.dp.toFloat(), 0F, 0F, Color.BLACK)
    }

    // Horizontal scale, in pixels per second
    private var scale = DEFAULT_SCALE
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Drag in progress: the offset it started from and how far it moved it
    private var dragging = false
    private var dragDirection = 1
    private var dragStartX = 0F
    private var dragStartOffset = 0L
    private var dragDelta = 0L
    private var lastLiveUpdate = 0L
    private var downX = 0F

    private var loadJob: Job? = null
    private var loadProgress = -1F
    private var lastLoadCheck = 0L

    private var pov1 = FloatArray(0)
    private var pov2 = FloatArray(0)
    private var lines1 = FloatArray(0)
    private var lines2 = FloatArray(0)
    // Loudness references, smoothed so the strips don't pump
    private var top1 = Float.NaN
    private var top2 = Float.NaN
    private var match = Float.NaN
    private var lastMatch = 0L

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scale = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            invalidate()
            return true
        }
    })

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) invalidate() else {
            loadJob?.cancel()
            loadJob = null
        }
    }

    override fun onDetachedFromWindow() {
        loadJob?.cancel()
        loadJob = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val delegate = delegate ?: return
        val timeline = delegate.state.value?.timeline ?: return
        val width = width
        if (width <= 0) return
        val now = SystemClock.elapsedRealtime()
        val pov1Time = delegate.currentPov1Time()
        val offset = if (dragging) dragStartOffset + dragDelta else timeline.offsetAt(pov1Time)
        val spanMs = (width / scale * 1000).toLong()
        val steps = (spanMs / AudioEnvelope.HOP_MS).toInt() + 2
        if (pov1.size != steps) {
            pov1 = FloatArray(steps)
            pov2 = FloatArray(steps)
        }
        val from1 = snap(pov1Time - spanMs / 2)
        val from2 = snap(pov1Time + offset - spanMs / 2)
        val audio = delegate.audio()
        val complete = audio != null && audio.cache.peek(from1, pov1) and audio.cache.peek(from2, pov2)
        // Only each POV's own footage counts
        mask(pov1, from1, timeline.start, timeline.split)
        mask(pov2, from2, timeline.split, timeline.end)
        if (!complete && now - lastLoadCheck >= LOAD_CHECK) {
            lastLoadCheck = now
            load(timeline, pov1Time, offset)
        }

        val stripGap = 4.dp
        val labelHeight = textPaint.textSize + 4.dp
        val area = height - labelHeight
        val stripHeight = if (overlay) area else (area - stripGap) / 2
        val top1Y = labelHeight
        val top2Y = if (overlay) labelHeight else labelHeight + stripHeight + stripGap
        canvas.drawRect(0F, top1Y, width.toFloat(), top1Y + stripHeight, stripPaint)
        if (!overlay) canvas.drawRect(0F, top2Y, width.toFloat(), top2Y + stripHeight, stripPaint)
        top1 = smoothTop(top1, pov1)
        top2 = smoothTop(top2, pov2)
        val centerX = width / 2F
        // Pixel x shows the moment (x - centreX) / scale seconds from the playhead
        val startOffset1 = pov1Time - spanMs / 2 - from1
        val startOffset2 = pov1Time + offset - spanMs / 2 - from2
        lines1 = drawStrip(canvas, pov1, startOffset1, top1, top1Y, stripHeight, pov1Paint, lines1, width)
        lines2 = drawStrip(canvas, pov2, startOffset2, top2, top2Y, stripHeight, pov2Paint, lines2, width)
        canvas.drawLine(centerX, labelHeight, centerX, height.toFloat(), playheadPaint)

        if (now - lastMatch >= MATCH_INTERVAL || dragging) {
            lastMatch = now
            match = similarity(pov1, pov2)
        }
        textPaint.color = pov1Paint.color
        canvas.drawText(context.getString(R.string.dual_view_pov, 1), 4.dp.toFloat(), textPaint.textSize, textPaint)
        val pov1Width = textPaint.measureText(context.getString(R.string.dual_view_pov, 1))
        textPaint.color = POV2_COLOR
        canvas.drawText(context.getString(R.string.dual_view_pov, 2), 12.dp + pov1Width, textPaint.textSize, textPaint)
        textPaint.color = Color.WHITE
        val info = when {
            loadProgress in 0F..0.999F -> context.getString(R.string.dual_view_reading_audio, (loadProgress * 100).toInt())
            match.isNaN() -> DualViewDelegate.formatShift(offset - timeline.split)
            else -> context.getString(R.string.dual_view_waveform_info, DualViewDelegate.formatShift(offset - timeline.split), (match * 100).toInt())
        }
        canvas.drawText(info, width - textPaint.measureText(info) - 4.dp, textPaint.textSize, textPaint)

        // Follow playback; while paused, only keep an eye on what's being read
        val playing = delegate.isMainPlaying()
        if (isShown) {
            if (playing || dragging) postInvalidateOnAnimation() else postInvalidateDelayed(IDLE_REFRESH)
        }
    }

    /**
     * Draws [values] (energies, the first one [startOffsetMs] before the left edge) as mirrored
     * bars of their loudness in dB, [top] being the loudest; stretches not read yet are shaded
     */
    private fun drawStrip(canvas: Canvas, values: FloatArray, startOffsetMs: Long, top: Float, y: Float, height: Float, paint: Paint, lines: FloatArray, width: Int): FloatArray {
        val buffer = if (lines.size == width * 4) lines else FloatArray(width * 4)
        val middle = y + height / 2
        val msPerPixel = 1000F / scale
        var count = 0
        var missingFrom = -1
        for (x in 0 until width) {
            val firstMs = startOffsetMs + x * msPerPixel
            val first = (firstMs / AudioEnvelope.HOP_MS).toInt()
            val last = max(first, ((firstMs + msPerPixel) / AudioEnvelope.HOP_MS).toInt() - 1)
            var loudest = Float.NaN
            for (i in first..last) if (i in values.indices && !values[i].isNaN() && (loudest.isNaN() || values[i] > loudest)) loudest = values[i]
            if (loudest.isNaN()) {
                if (missingFrom < 0) missingFrom = x
                continue
            }
            if (missingFrom >= 0) {
                canvas.drawRect(missingFrom.toFloat(), y, x.toFloat(), y + height, missingPaint)
                missingFrom = -1
            }
            val level = if (top.isNaN()) 0F else ((decibels(loudest) - (top - RANGE_DB)) / RANGE_DB).coerceIn(0F, 1F)
            val half = max(0.5F, level * height / 2)
            buffer[count++] = x.toFloat()
            buffer[count++] = middle - half
            buffer[count++] = x.toFloat()
            buffer[count++] = middle + half
        }
        if (missingFrom >= 0) canvas.drawRect(missingFrom.toFloat(), y, width.toFloat(), y + height, missingPaint)
        canvas.drawLines(buffer, 0, count, paint)
        return buffer
    }

    /**
     * Reads the audio around the strips' moments, a stretch at a time
     */
    private fun load(timeline: DualViewTimeline, pov1Time: Long, offset: Long) {
        if (loadJob?.isActive == true) return
        val audio = delegate?.audio() ?: return
        val scope = findViewTreeLifecycleOwner()?.lifecycleScope ?: return
        val pov1 = (pov1Time - LOAD_AROUND).coerceAtLeast(timeline.start)..(pov1Time + LOAD_AROUND).coerceAtMost(timeline.split)
        val pov2 = (pov1Time + offset - LOAD_AROUND).coerceAtLeast(timeline.split)..(pov1Time + offset + LOAD_AROUND).coerceAtMost(timeline.end)
        val ranges = listOf(pov1, pov2).filter { it.last > it.first }
        if (ranges.isEmpty() || ranges.all { audio.cache.has(it) }) return
        loadJob = scope.launch {
            loadProgress = 0F
            try {
                audio.cache.get(ranges) { progress -> post { loadProgress = progress } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unreadable audio: the strips stay shaded
            } finally {
                loadProgress = -1F
                invalidate()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val delegate = delegate ?: return false
        val timeline = delegate.state.value?.timeline ?: return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                dragStartX = event.x
                // Dragging POV 2 to the right shows it later: its moments under the playhead come earlier
                dragDirection = if (overlay || event.y > height / 2F) -1 else 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (dragging) endDrag(false)
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                if (!dragging && abs(event.x - downX) > touchSlop) {
                    dragging = true
                    dragStartX = event.x
                    dragStartOffset = timeline.offsetAt(delegate.currentPov1Time())
                    dragDelta = 0L
                }
                if (dragging) {
                    dragDelta = (dragDirection * (event.x - dragStartX) / scale * 1000).roundToLong()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLiveUpdate >= LIVE_UPDATE) {
                        lastLiveUpdate = now
                        delegate.setOffsetHere(dragStartOffset + dragDelta, persist = false)
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> if (dragging) endDrag(true)
            MotionEvent.ACTION_CANCEL -> if (dragging) endDrag(true)
        }
        return true
    }

    private fun endDrag(commit: Boolean) {
        dragging = false
        if (commit) delegate?.setOffsetHere(dragStartOffset + dragDelta, persist = true)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(DEFAULT_HEIGHT.dp, heightMeasureSpec))
    }

    companion object {
        private const val POV2_COLOR = 0xFF4FC3F7.toInt()
        private const val DEFAULT_SCALE = 100F
        private const val MIN_SCALE = 20F
        private const val MAX_SCALE = 800F
        private const val RANGE_DB = 35F
        private const val LOAD_AROUND = 30_000L
        private const val LOAD_CHECK = 500L
        private const val LIVE_UPDATE = 250L
        private const val MATCH_INTERVAL = 250L
        private const val IDLE_REFRESH = 250L
        private const val DEFAULT_HEIGHT = 120

        private fun snap(time: Long) = time.floorDiv(AudioEnvelope.HOP_MS) * AudioEnvelope.HOP_MS

        private fun decibels(energy: Float) = 10F * log10(energy + 1e-10F)

        /**
         * Hides the steps of [values] (the first one at [fromMs]) outside [start, end)
         */
        private fun mask(values: FloatArray, fromMs: Long, start: Long, end: Long) {
            for (i in values.indices) {
                val time = fromMs + i * AudioEnvelope.HOP_MS
                if (time < start || time >= end) values[i] = Float.NaN
            }
        }

        /**
         * [previous] eased towards the loudest of [values], in dB
         */
        private fun smoothTop(previous: Float, values: FloatArray): Float {
            var loudest = Float.NaN
            for (value in values) if (!value.isNaN() && (loudest.isNaN() || value > loudest)) loudest = value
            if (loudest.isNaN()) return previous
            val target = decibels(loudest)
            return if (previous.isNaN()) target else previous + 0.15F * (target - previous)
        }

        /**
         * How much the onsets of [a] and [b] (aligned energies) agree, from 0 to 1: their correlation
         */
        fun similarity(a: FloatArray, b: FloatArray): Float {
            val size = minOf(a.size, b.size)
            var lastA = Float.NaN
            var lastB = Float.NaN
            var n = 0
            var sumA = 0.0
            var sumB = 0.0
            var sumAA = 0.0
            var sumBB = 0.0
            var sumAB = 0.0
            for (i in 0 until size) {
                if (a[i].isNaN() || b[i].isNaN()) {
                    lastA = Float.NaN
                    lastB = Float.NaN
                    continue
                }
                val logA = decibels(a[i])
                val logB = decibels(b[i])
                if (!lastA.isNaN()) {
                    val x = max(0F, logA - lastA).toDouble()
                    val y = max(0F, logB - lastB).toDouble()
                    n++
                    sumA += x
                    sumB += y
                    sumAA += x * x
                    sumBB += y * y
                    sumAB += x * y
                }
                lastA = logA
                lastB = logB
            }
            if (n < MIN_COMPARED) return Float.NaN
            val covariance = sumAB / n - sumA / n * sumB / n
            val deviations = sqrt((sumAA / n - (sumA / n) * (sumA / n)) * (sumBB / n - (sumB / n) * (sumB / n)))
            if (deviations <= 1e-12) return Float.NaN
            return (covariance / deviations).toFloat().coerceIn(0F, 1F)
        }

        private const val MIN_COMPARED = 100
    }
}
