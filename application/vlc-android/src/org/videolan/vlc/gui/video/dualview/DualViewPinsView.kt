/*
 * ************************************************************************
 *  DualViewPinsView.kt
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
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import org.videolan.medialibrary.Tools
import org.videolan.tools.dp
import org.videolan.vlc.R
import kotlin.math.abs

/**
 * Pins above the seek bar marking where POV 1 starts, where POV 2 starts (the split) and where it
 * ends, and ticks for the cuts. Drag a pin to move it: the player shows where it is while it
 * moves. Tap a cut's tick to remove it.
 *
 * Touches away from the pins go through to the seek bar and the player's gestures.
 */
class DualViewPinsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var delegate: DualViewDelegate? = null

    var timeline: DualViewTimeline? = null
        set(value) {
            field = value
            if (dragging == null) invalidate()
        }

    private sealed class Pin {
        object Start : Pin()
        object Split : Pin()
        object End : Pin()
        data class Cut(val index: Int) : Pin()
    }

    private val splitMarker = AppCompatResources.getDrawable(context, R.drawable.ic_dual_view_marker)
    private val boundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.orange500) }
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.dp.toFloat()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12.dp.toFloat()
        textAlign = Paint.Align.CENTER
        setShadowLayer(3.dp.toFloat(), 0F, 0F, Color.BLACK)
    }
    private val path = Path()
    private val touchRadius = 24.dp
    private val pinSize = 24.dp
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var dragging: Pin? = null
    private var dragTime = 0L
    private var downX = 0F
    private var moved = false
    private var lastPreview = 0L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.dual_view_pins)
        for ((label, move) in listOf(
                R.string.dual_view_start_earlier to { t: DualViewTimeline -> Triple(t.start - STEP, t.split, t.end) },
                R.string.dual_view_start_later to { t: DualViewTimeline -> Triple(t.start + STEP, t.split, t.end) },
                R.string.dual_view_split_earlier to { t: DualViewTimeline -> Triple(t.start, t.split - STEP, t.end) },
                R.string.dual_view_split_later to { t: DualViewTimeline -> Triple(t.start, t.split + STEP, t.end) },
                R.string.dual_view_end_earlier to { t: DualViewTimeline -> Triple(t.start, t.split, t.end - STEP) },
                R.string.dual_view_end_later to { t: DualViewTimeline -> Triple(t.start, t.split, t.end + STEP) })) {
            ViewCompat.addAccessibilityAction(this, context.getString(label)) { _, _ ->
                val current = timeline ?: return@addAccessibilityAction false
                val (start, split, end) = move(current)
                delegate?.setBounds(start.coerceAtLeast(0L), split, end.coerceAtMost(current.length))
                true
            }
        }
    }

    private val contentWidth: Int
        get() = width - paddingLeft - paddingRight

    private fun xOf(time: Long, length: Long) = paddingLeft + contentWidth * (time.toFloat() / length)

    private fun timeOf(x: Float, length: Long) = ((x - paddingLeft) / contentWidth * length).toLong().coerceIn(0L, length)

    private fun timeOf(pin: Pin, timeline: DualViewTimeline) = when (pin) {
        Pin.Start -> timeline.start
        Pin.Split -> timeline.split
        Pin.End -> timeline.end
        is Pin.Cut -> timeline.segments.getOrNull(pin.index)?.from ?: 0L
    }

    override fun onDraw(canvas: Canvas) {
        val timeline = timeline ?: return
        if (contentWidth <= 0) return
        val length = timeline.length
        val bottom = height.toFloat()
        val dragged = dragging
        fun shownTime(pin: Pin) = if (pin == dragged) dragTime else timeOf(pin, timeline)
        // Cuts: thin ticks
        for (index in 1 until timeline.segments.size) {
            val x = xOf(shownTime(Pin.Cut(index)), length)
            canvas.drawLine(x, bottom - pinSize * 0.7F, x, bottom, cutPaint)
            canvas.drawCircle(x, bottom - pinSize * 0.7F, 3.dp.toFloat(), cutPaint)
        }
        // Start and end: flags pointing into the POVs
        drawFlag(canvas, xOf(shownTime(Pin.Start), length), bottom, 1F)
        drawFlag(canvas, xOf(shownTime(Pin.End), length), bottom, -1F)
        splitMarker?.let {
            val x = xOf(shownTime(Pin.Split), length).toInt()
            it.setBounds(x - pinSize / 2, (bottom - pinSize).toInt(), x + pinSize / 2, bottom.toInt())
            it.draw(canvas)
        }
        if (dragged != null) {
            val x = xOf(dragTime, length).coerceIn(labelPaint.textSize * 2, width - labelPaint.textSize * 2)
            canvas.drawText(Tools.millisToString(dragTime), x, bottom - pinSize - 4.dp, labelPaint)
        }
    }

    private fun drawFlag(canvas: Canvas, x: Float, bottom: Float, direction: Float) {
        val top = bottom - pinSize
        path.reset()
        path.moveTo(x, bottom)
        path.lineTo(x, top)
        path.lineTo(x + direction * pinSize * 0.5F, top + pinSize * 0.25F)
        path.lineTo(x, top + pinSize * 0.5F)
        path.close()
        canvas.drawPath(path, boundPaint)
        canvas.drawRect(minOf(x, x + direction * 2.dp), top, maxOf(x, x + direction * 2.dp), bottom, boundPaint)
    }

    /**
     * The pin nearest to [x], if within reach
     */
    private fun pinAt(x: Float, timeline: DualViewTimeline): Pin? {
        val pins = listOf(Pin.Split, Pin.Start, Pin.End) + (1 until timeline.segments.size).map { Pin.Cut(it) }
        return pins.map { it to abs(xOf(timeOf(it, timeline), timeline.length) - x) }
                .filter { it.second <= touchRadius }
                .minByOrNull { it.second }?.first
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val timeline = timeline ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only the pins' own area: the rest belongs to the seek bar and the player
                if (event.y < height - pinSize - touchRadius / 2) return false
                val pin = pinAt(event.x, timeline) ?: return false
                dragging = pin
                dragTime = timeOf(pin, timeline)
                downX = event.x
                moved = false
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pin = dragging ?: return false
                if (!moved && abs(event.x - downX) < touchSlop) return true
                moved = true
                dragTime = clamp(pin, timeOf(event.x, timeline.length), timeline)
                val now = SystemClock.elapsedRealtime()
                if (now - lastPreview >= PREVIEW_INTERVAL) {
                    lastPreview = now
                    delegate?.preview(dragTime)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val pin = dragging ?: return false
                dragging = null
                if (moved) commit(pin, timeline) else if (pin is Pin.Cut) askRemove(pin.index)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = null
                invalidate()
            }
        }
        return true
    }

    /**
     * Keeps the POVs long enough and the cuts between their neighbours
     */
    private fun clamp(pin: Pin, time: Long, timeline: DualViewTimeline): Long {
        val min = DualViewTimeline.MIN_POV_LENGTH
        return when (pin) {
            Pin.Start -> time.coerceIn(0L, timeline.split - min)
            Pin.Split -> time.coerceIn(timeline.start + min, timeline.end - min)
            Pin.End -> time.coerceIn(timeline.split + min, timeline.length)
            is Pin.Cut -> {
                val low = timeline.segments[pin.index - 1].from + DualViewTimeline.MIN_SEGMENT
                val high = timeline.segmentEnd(pin.index) - DualViewTimeline.MIN_SEGMENT
                if (high < low) timeOf(pin, timeline) else time.coerceIn(low, high)
            }
        }
    }

    private fun commit(pin: Pin, timeline: DualViewTimeline) {
        val delegate = delegate ?: return
        delegate.preview(dragTime, fast = false)
        when (pin) {
            Pin.Start -> delegate.setBounds(dragTime, timeline.split, timeline.end)
            Pin.Split -> delegate.setBounds(timeline.start, dragTime, timeline.end)
            Pin.End -> delegate.setBounds(timeline.start, timeline.split, dragTime)
            is Pin.Cut -> delegate.moveCut(pin.index, dragTime)
        }
    }

    private fun askRemove(index: Int) {
        val at = timeline?.segments?.getOrNull(index)?.from ?: return
        PopupMenu(context, this).apply {
            menu.add(context.getString(R.string.dual_view_remove_cut, Tools.millisToString(at)))
            setOnMenuItemClickListener {
                delegate?.removeCut(index)
                true
            }
            show()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = pinSize + 20.dp + paddingTop + paddingBottom
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(wanted, heightMeasureSpec))
    }

    companion object {
        private const val PREVIEW_INTERVAL = 150L
        private const val STEP = 1_000L
    }
}
