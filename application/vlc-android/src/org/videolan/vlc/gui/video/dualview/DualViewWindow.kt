/*
 * ************************************************************************
 *  DualViewWindow.kt
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
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.tools.dp
import org.videolan.vlc.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The floating window showing the other POV, above the video and under the player controls.
 * Drag it to move it, pinch it or drag its corner to resize it; it keeps the video's aspect ratio
 * and stays on screen.
 */
class DualViewWindow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    interface Callback {
        fun onSwap()
        fun onAudioFromWindow()
        fun onClose()
        fun isLocked(): Boolean
        fun onGeometryChanged(geometry: DualViewStore.Geometry)
    }

    var callback: Callback? = null
    lateinit var videoLayout: VLCVideoLayout
        private set
    private lateinit var label: TextView
    private lateinit var message: TextView
    private lateinit var status: TextView
    private lateinit var controls: View
    private lateinit var resizeHandle: View

    private var aspect = DEFAULT_ASPECT
    // Width of the video as a fraction of the screen's shorter side
    private var size = DEFAULT_SIZE
    // Position between the screen edges, from 0 to 1
    private var xFraction = 1F
    private var yFraction = 0F
    private val margin = 8.dp
    private val minVideoWidth = 120.dp
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var lastX = 0F
    private var lastY = 0F
    private var dragging = false
    private var canIntercept = true
    private var resizeStartX = 0F
    private var resizeStartY = 0F
    private var resizeStartWidth = 0F
    private var resizeLeft = 0F
    private var resizeTop = 0F

    private val hideControls = Runnable { controls.visibility = View.GONE }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performClick()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            callback?.onSwap()
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            resize(size * detector.scaleFactor, keepCenter = true)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) = saveGeometry()
    })

    private val parentLayoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) post { layoutWindow() }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        videoLayout = findViewById(R.id.dual_view_video)
        label = findViewById(R.id.dual_view_label)
        message = findViewById(R.id.dual_view_message)
        status = findViewById(R.id.dual_view_status)
        controls = findViewById(R.id.dual_view_controls)
        resizeHandle = findViewById(R.id.dual_view_resize)
        findViewById<View>(R.id.dual_view_swap).setOnClickListener {
            callback?.onSwap()
            showControls()
        }
        findViewById<View>(R.id.dual_view_audio).setOnClickListener {
            callback?.onAudioFromWindow()
            showControls()
        }
        findViewById<View>(R.id.dual_view_close).setOnClickListener { callback?.onClose() }
        resizeHandle.setOnTouchListener { view, event -> onResizeTouch(view, event) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.addOnLayoutChangeListener(parentLayoutListener)
    }

    override fun onDetachedFromWindow() {
        (parent as? View)?.removeOnLayoutChangeListener(parentLayoutListener)
        removeCallbacks(hideControls)
        super.onDetachedFromWindow()
    }

    fun show(geometry: DualViewStore.Geometry?) {
        geometry?.let {
            xFraction = it.x.coerceIn(0F, 1F)
            yFraction = it.y.coerceIn(0F, 1F)
            size = it.size
        }
        controls.visibility = View.GONE
        visibility = View.VISIBLE
        post { layoutWindow() }
    }

    fun hide() {
        removeCallbacks(hideControls)
        visibility = View.GONE
    }

    /**
     * Width / height of the video
     */
    fun setAspect(aspect: Float) {
        if (aspect <= 0F || abs(aspect - this.aspect) < 0.01F) return
        this.aspect = aspect
        layoutWindow()
    }

    fun setLabel(pov: Int, audible: Boolean) {
        label.text = context.getString(R.string.dual_view_pov, pov)
        val icon = if (audible) AppCompatResources.getDrawable(context, R.drawable.ic_dual_view_audio)?.apply { setBounds(0, 0, 14.dp, 14.dp) } else null
        label.setCompoundDrawables(icon, null, null, null)
    }

    fun setMessage(text: String?) {
        message.text = text
        message.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    fun setStatus(text: String?) {
        status.text = text
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (controls.visibility == View.VISIBLE) {
            removeCallbacks(hideControls)
            controls.visibility = View.GONE
        } else showControls()
        return true
    }

    private fun showControls() {
        controls.visibility = View.VISIBLE
        removeCallbacks(hideControls)
        postDelayed(hideControls, CONTROLS_TIMEOUT)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Let a locked player handle the touch
        if (callback?.isLocked() == true) return false
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                dragging = false
                canIntercept = !isOnResizeHandle(event)
            }
            // Take over drags and pinches that start on the buttons
            MotionEvent.ACTION_POINTER_DOWN -> return canIntercept
            MotionEvent.ACTION_MOVE -> return canIntercept && (abs(event.rawX - lastX) > touchSlop || abs(event.rawY - lastY) > touchSlop)
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                if (dragging || abs(dx) > touchSlop || abs(dy) > touchSlop) {
                    dragging = true
                    place(translationX + dx, translationY + dy)
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
            // Continue from the remaining finger, without jumping
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                // Raw and view coordinates differ by the same amount for every finger
                lastX = event.getX(remaining) + event.rawX - event.getX(0)
                lastY = event.getY(remaining) + event.rawY - event.getY(0)
                dragging = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) saveGeometry()
                dragging = false
            }
        }
        return true
    }

    private fun onResizeTouch(view: View, event: MotionEvent): Boolean {
        if (callback?.isLocked() == true) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeStartX = event.rawX
                resizeStartY = event.rawY
                resizeStartWidth = (layoutParams.width - paddingLeft - paddingRight).toFloat()
                resizeLeft = translationX
                resizeTop = translationY
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - resizeStartX
                val dy = (event.rawY - resizeStartY) * aspect
                val shorter = shorterSide() ?: return true
                resize((resizeStartWidth + if (abs(dx) > abs(dy)) dx else dy) / shorter, keepCenter = false)
                place(resizeLeft, resizeTop)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> saveGeometry()
        }
        return true
    }

    private fun isOnResizeHandle(event: MotionEvent) = event.x >= resizeHandle.left && event.x <= resizeHandle.right
            && event.y >= resizeHandle.top && event.y <= resizeHandle.bottom

    private fun shorterSide(): Int? {
        val parent = parent as? View ?: return null
        return min(parent.width, parent.height).takeIf { it > 0 }
    }

    private fun resize(newSize: Float, keepCenter: Boolean) {
        val centerX = translationX + layoutParams.width / 2F
        val centerY = translationY + layoutParams.height / 2F
        size = newSize
        layoutWindow()
        if (keepCenter) place(centerX - layoutParams.width / 2F, centerY - layoutParams.height / 2F)
    }

    /**
     * Sizes the window for the video and the screen, and places it where it was
     */
    private fun layoutWindow() {
        val parent = parent as? View ?: return
        val parentWidth = parent.width
        val parentHeight = parent.height
        if (parentWidth <= 0 || parentHeight <= 0) return
        val horizontalPadding = paddingLeft + paddingRight
        val verticalPadding = paddingTop + paddingBottom
        val maxWidth = minOf(parentWidth * MAX_FRACTION - horizontalPadding, (parentHeight * MAX_FRACTION - verticalPadding) * aspect)
        val videoWidth = (size * min(parentWidth, parentHeight)).coerceIn(minOf(minVideoWidth.toFloat(), maxWidth), maxWidth)
        size = videoWidth / min(parentWidth, parentHeight)
        val width = (videoWidth + horizontalPadding).roundToInt()
        val height = (videoWidth / aspect + verticalPadding).roundToInt()
        val params = layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            layoutParams = params
        }
        translationX = margin + xFraction * (parentWidth - width - 2 * margin).coerceAtLeast(0)
        translationY = margin + yFraction * (parentHeight - height - 2 * margin).coerceAtLeast(0)
    }

    /**
     * Moves the window's top left corner to ([left], [top]), keeping it on screen
     */
    private fun place(left: Float, top: Float) {
        val parent = parent as? View ?: return
        val rangeX = (parent.width - layoutParams.width - 2 * margin).coerceAtLeast(0)
        val rangeY = (parent.height - layoutParams.height - 2 * margin).coerceAtLeast(0)
        translationX = left.coerceIn(margin.toFloat(), (margin + rangeX).toFloat())
        translationY = top.coerceIn(margin.toFloat(), (margin + rangeY).toFloat())
        xFraction = if (rangeX > 0) (translationX - margin) / rangeX else 0F
        yFraction = if (rangeY > 0) (translationY - margin) / rangeY else 0F
    }

    private fun saveGeometry() {
        callback?.onGeometryChanged(DualViewStore.Geometry(xFraction, yFraction, size))
    }

    companion object {
        private const val DEFAULT_ASPECT = 16F / 9F
        private const val DEFAULT_SIZE = 0.5F
        private const val MAX_FRACTION = 0.9F
        private const val CONTROLS_TIMEOUT = 3_000L
    }
}
