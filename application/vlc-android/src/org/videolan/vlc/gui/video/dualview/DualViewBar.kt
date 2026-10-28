/*
 * ************************************************************************
 *  DualViewBar.kt
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
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.OnRepeatListenerKey
import org.videolan.vlc.gui.helpers.OnRepeatListenerTouch

/**
 * Dual view controls above the seek bar: audible POV, offset by frames, audio sync, the waveform
 * tool, cuts, scan, swap. Playback goes on while they are used.
 */
class DualViewBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private var delegate: DualViewDelegate? = null
    private lateinit var statusRow: View
    private lateinit var statusText: TextView
    private lateinit var statusProgress: View
    private lateinit var undo: View
    private lateinit var cancel: View
    private lateinit var audioPov: MaterialButtonToggleGroup
    private lateinit var offsetValue: TextView
    private lateinit var syncHere: View
    private lateinit var scan: View
    private lateinit var waveform: DualViewWaveformView
    private lateinit var waveformToggle: MaterialButton
    private lateinit var overlayToggle: MaterialButton

    private val refresh = object : Runnable {
        override fun run() {
            updateOffset()
            postDelayed(this, OFFSET_REFRESH)
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        statusRow = findViewById(R.id.dual_view_status_row)
        statusText = findViewById(R.id.dual_view_status_text)
        statusProgress = findViewById(R.id.dual_view_status_progress)
        undo = findViewById(R.id.dual_view_undo)
        cancel = findViewById(R.id.dual_view_cancel)
        audioPov = findViewById(R.id.dual_view_audio_pov)
        offsetValue = findViewById(R.id.dual_view_offset_value)
        syncHere = findViewById(R.id.dual_view_sync_here)
        scan = findViewById(R.id.dual_view_scan)
        waveform = findViewById(R.id.dual_view_waveform)
        waveformToggle = findViewById(R.id.dual_view_waveform_toggle)
        overlayToggle = findViewById(R.id.dual_view_overlay_toggle)
        findViewById<TextView>(R.id.dual_view_audio_pov1).text = context.getString(R.string.dual_view_pov, 1)
        findViewById<TextView>(R.id.dual_view_audio_pov2).text = context.getString(R.string.dual_view_pov, 2)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun bind(delegate: DualViewDelegate, owner: LifecycleOwner) {
        this.delegate = delegate
        waveform.delegate = delegate
        audioPov.addOnButtonCheckedListener { _, id, checked ->
            if (checked) delegate.setAudioPov(if (id == R.id.dual_view_audio_pov2) 2 else 1)
        }
        val decrease = OnClickListener { delegate.nudge(-1) }
        val increase = OnClickListener { delegate.nudge(1) }
        val minus = findViewById<View>(R.id.dual_view_offset_minus)
        val plus = findViewById<View>(R.id.dual_view_offset_plus)
        minus.setOnTouchListener(OnRepeatListenerTouch(clickListener = decrease, listenerLifecycle = owner.lifecycle))
        plus.setOnTouchListener(OnRepeatListenerTouch(clickListener = increase, listenerLifecycle = owner.lifecycle))
        minus.setOnKeyListener(OnRepeatListenerKey(clickListener = decrease, listenerLifecycle = owner.lifecycle))
        plus.setOnKeyListener(OnRepeatListenerKey(clickListener = increase, listenerLifecycle = owner.lifecycle))
        minus.setOnClickListener(decrease)
        plus.setOnClickListener(increase)
        syncHere.setOnClickListener { delegate.syncHere() }
        scan.setOnClickListener { delegate.scan() }
        findViewById<View>(R.id.dual_view_cut_here).setOnClickListener { delegate.addCutHere() }
        findViewById<View>(R.id.dual_view_swap_views).setOnClickListener { delegate.swap() }
        findViewById<View>(R.id.dual_view_turn_off).setOnClickListener { delegate.close() }
        undo.setOnClickListener { delegate.undo() }
        cancel.setOnClickListener { delegate.cancelSync() }
        waveformToggle.addOnCheckedChangeListener { _, checked ->
            waveform.visibility = if (checked) View.VISIBLE else View.GONE
            overlayToggle.visibility = if (checked) View.VISIBLE else View.GONE
        }
        overlayToggle.addOnCheckedChangeListener { _, checked -> waveform.overlay = checked }
        delegate.state.observe(owner) { updateState(it) }
        delegate.status.observe(owner) { updateStatus() }
        delegate.canUndo.observe(owner) { updateStatus() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    private fun updateState(state: DualViewDelegate.State?) {
        if (state == null) return
        val checked = if (state.audioPov == 2) R.id.dual_view_audio_pov2 else R.id.dual_view_audio_pov1
        if (audioPov.checkedButtonId != checked) audioPov.check(checked)
        updateOffset()
    }

    /**
     * The offset at the current moment, and which segment holds it when the video has cuts
     */
    private fun updateOffset() {
        val delegate = delegate ?: return
        val timeline = delegate.state.value?.timeline ?: return
        if (!isShown) return
        val pov1Time = delegate.currentPov1Time()
        val shift = DualViewDelegate.formatShift(timeline.offsetAt(pov1Time) - timeline.split)
        offsetValue.text = if (timeline.segments.size == 1) shift
        else context.getString(R.string.dual_view_offset_segment, shift, timeline.segmentIndexAt(pov1Time) + 1, timeline.segments.size)
    }

    private fun updateStatus() {
        val delegate = delegate ?: return
        val status = delegate.status.value
        val working = status is DualViewDelegate.Status.Reading || status is DualViewDelegate.Status.Matching
        val canUndo = delegate.canUndo.value == true && !working
        statusRow.visibility = if (status == null && !canUndo) View.GONE else View.VISIBLE
        statusText.text = delegate.statusText(status) ?: ""
        statusProgress.visibility = if (working) View.VISIBLE else View.GONE
        cancel.visibility = if (working) View.VISIBLE else View.GONE
        undo.visibility = if (canUndo) View.VISIBLE else View.GONE
        syncHere.isEnabled = !working
        scan.isEnabled = !working
    }

    companion object {
        private const val OFFSET_REFRESH = 500L
    }
}
