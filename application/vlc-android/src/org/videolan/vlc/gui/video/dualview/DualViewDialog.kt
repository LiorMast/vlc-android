/*
 * ************************************************************************
 *  DualViewDialog.kt
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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.medialibrary.Tools
import org.videolan.vlc.R
import org.videolan.vlc.databinding.DialogDualViewBinding
import org.videolan.vlc.gui.dialogs.PlaybackBottomSheetDialogFragment
import org.videolan.vlc.gui.helpers.OnRepeatListenerKey
import org.videolan.vlc.gui.helpers.OnRepeatListenerTouch
import org.videolan.vlc.gui.video.VideoPlayerActivity

/**
 * Dual view controls: split, audio, offset, audio sync, swap
 */
class DualViewDialog : PlaybackBottomSheetDialogFragment() {

    private lateinit var binding: DialogDualViewBinding

    private val delegate: DualViewDelegate?
        get() = (activity as? VideoPlayerActivity)?.dualViewDelegate

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = DialogDualViewBinding.inflate(inflater, container, false)
        binding.audioPov1.text = getString(R.string.dual_view_pov, 1)
        binding.audioPov2.text = getString(R.string.dual_view_pov, 2)
        binding.resume.setOnClickListener { delegate?.resume() }
        binding.splitNow.setOnClickListener { delegate?.splitNow() }
        binding.splitMiddle.setOnClickListener { delegate?.let { it.splitMiddle(it.syncOnSplitMiddle) } }
        binding.syncOnSplitMiddle.isChecked = delegate?.syncOnSplitMiddle ?: true
        binding.syncOnSplitMiddle.setOnCheckedChangeListener { _, checked -> delegate?.syncOnSplitMiddle = checked }
        binding.audioPov.addOnButtonCheckedListener { _, id, checked ->
            if (checked) delegate?.setAudioPov(if (id == R.id.audio_pov2) 2 else 1)
        }
        val decrease = View.OnClickListener { delegate?.nudge(-1) }
        val increase = View.OnClickListener { delegate?.nudge(1) }
        binding.offsetMinus.setOnTouchListener(OnRepeatListenerTouch(clickListener = decrease, listenerLifecycle = lifecycle))
        binding.offsetPlus.setOnTouchListener(OnRepeatListenerTouch(clickListener = increase, listenerLifecycle = lifecycle))
        binding.offsetMinus.setOnKeyListener(OnRepeatListenerKey(clickListener = decrease, listenerLifecycle = lifecycle))
        binding.offsetPlus.setOnKeyListener(OnRepeatListenerKey(clickListener = increase, listenerLifecycle = lifecycle))
        binding.offsetMinus.setOnClickListener(decrease)
        binding.offsetPlus.setOnClickListener(increase)
        binding.syncAudio.setOnClickListener { delegate?.syncWithAudio() }
        binding.swap.setOnClickListener { delegate?.swap() }
        binding.turnOff.setOnClickListener {
            delegate?.close()
            dismiss()
        }
        binding.cancelSync.setOnClickListener { delegate?.cancelSync() }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val delegate = delegate ?: run {
            dismiss()
            return
        }
        delegate.state.observe(viewLifecycleOwner) { updateState(it) }
        delegate.status.observe(viewLifecycleOwner) { updateStatus(it) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updateTimes()
                    delay(TIME_REFRESH)
                }
            }
        }
    }

    private fun updateTimes() {
        val delegate = delegate ?: return
        val length = playbackService?.length ?: return
        binding.splitNowTime.text = Tools.millisToString(delegate.currentMainTime().coerceAtLeast(0L))
        binding.splitMiddleTime.text = Tools.millisToString(length / 2)
        val saved = if (delegate.isActive) null else delegate.savedSplit()
        binding.resume.visibility = if (saved == null) View.GONE else View.VISIBLE
        if (saved != null) binding.resume.text = getString(R.string.dual_view_resume, Tools.millisToString(saved.split), DualViewDelegate.formatShift(saved.offset - saved.split))
    }

    private fun updateState(state: DualViewDelegate.State?) {
        binding.activeControls.visibility = if (state == null) View.GONE else View.VISIBLE
        updateTimes()
        if (state == null) return
        binding.splitInfo.text = getString(R.string.dual_view_split_at, Tools.millisToString(state.timeline.split))
        binding.offsetValue.text = DualViewDelegate.formatShift(state.timeline.shift)
        val checked = if (state.audioPov == 2) R.id.audio_pov2 else R.id.audio_pov1
        if (binding.audioPov.checkedButtonId != checked) binding.audioPov.check(checked)
    }

    private fun updateStatus(status: DualViewDelegate.Status?) {
        val working = status is DualViewDelegate.Status.Reading
        binding.statusRow.visibility = if (status == null) View.GONE else View.VISIBLE
        binding.statusText.text = delegate?.statusText(status, detailed = true)
        binding.statusProgress.visibility = if (working) View.VISIBLE else View.GONE
        binding.cancelSync.visibility = if (working) View.VISIBLE else View.GONE
        binding.syncAudio.isEnabled = !working
    }

    override fun onServiceAvailable() = updateTimes()

    override fun onMediaChanged() = updateTimes()

    override fun getDefaultState() = BottomSheetBehavior.STATE_EXPANDED

    override fun needToManageOrientation() = true

    override fun initialFocusedView(): View = binding.splitNow

    override fun allowRemote() = true

    companion object {
        private const val TIME_REFRESH = 500L

        fun newInstance() = DualViewDialog()
    }
}
