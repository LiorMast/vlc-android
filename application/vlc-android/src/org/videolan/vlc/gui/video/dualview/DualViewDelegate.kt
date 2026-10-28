/*
 * ************************************************************************
 *  DualViewDelegate.kt
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

import android.net.Uri
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.widget.ViewStubCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.videolan.libvlc.MediaPlayer
import org.videolan.tools.Settings
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.gui.video.VideoPlayerActivity
import org.videolan.vlc.gui.video.dualview.audio.AudioSync
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Dual view: plays the two points of view of a video at once, one in the main player and the
 * other in a floating window, for videos holding two recordings of the same event one after the
 * other.
 *
 * The main player keeps playing the whole file as usual; a second player shows the moment
 * matching it in the other POV, and only the player showing the chosen POV is heard.
 */
class DualViewDelegate(private val activity: VideoPlayerActivity) : DualViewSync.Listener, DualViewWindow.Callback {

    /**
     * @param location where the video is, as the media library knows it
     * @param playbackUri the address VLC plays
     * @param windowPov the POV the window shows
     */
    data class State(val location: Uri, val playbackUri: Uri, val timeline: DualViewTimeline, val audioPov: Int, val windowPov: Int)

    sealed class Status {
        data class Reading(val progress: Float) : Status()
        data class Synced(val shift: Long) : Status()
        object NoMatch : Status()
        object Unreadable : Status()
        object TooShort : Status()
    }

    val state = MutableLiveData<State?>(null)
    val status = MutableLiveData<Status?>(null)

    val isActive: Boolean
        get() = state.value != null

    /**
     * The volume the user set, given to the player showing the audible POV
     */
    var volume = 100
        private set

    var syncOnSplitMiddle: Boolean
        get() = store.syncOnSplitMiddle
        set(value) {
            store.syncOnSplitMiddle = value
        }

    private val store by lazy(LazyThreadSafetyMode.NONE) { DualViewStore(activity) }
    private var player: DualViewPlayer? = null
    private var sync: DualViewSync? = null
    private var window: DualViewWindow? = null
    private var counterpartState = CounterpartState.PLAYING
    private var syncJob: Job? = null
    // Brought back once the main player plays the same video again
    private var restoreOnPlaying: State? = null
    // VLC doesn't report a new time after a seek while paused: remember where it went
    private var seekTarget = -1L
    private var seekReported = -1L
    private val hideStatus = Runnable { window?.setStatus(null) }
    private var frameDuration = 0.0
    // Offset the current run of nudges started from, and how many frames they moved it
    private var nudgeBase = 0L
    private var nudges = 0
    // Where the paused main player was last moved to show its reported moment exactly
    private var alignedAt = -1L

    /**
     * Whether dual view can be used for what the player plays now
     */
    fun isAvailable(): Boolean {
        val service = activity.service ?: return false
        return !Settings.showTvUi && activity.displayManager.isPrimary && !activity.displayManager.isOnRenderer && !PlaybackService.hasRenderer()
                && service.currentMediaWrapper != null && service.isSeekable && service.length > 0 && service.videoTracksCount > 0
    }

    /**
     * The split saved for the current video, if any
     */
    fun savedSplit(): DualViewStore.Saved? {
        val service = activity.service ?: return null
        val location = service.currentMediaWrapper?.uri ?: return null
        return store.load(location, service.length)
    }

    fun resume() {
        val saved = savedSplit() ?: return
        val length = activity.service?.length ?: return
        start(DualViewTimeline(length, saved.split, saved.offset), saved.audioPov)
    }

    /**
     * POV 2 starts where the main player is now
     */
    fun splitNow() = split(currentMainTime(), false)

    /**
     * POV 2 starts in the middle of the video, then optionally match the audio for the exact offset
     */
    fun splitMiddle(syncWithAudio: Boolean) = split((activity.service?.length ?: 0L) / 2, syncWithAudio)

    private fun split(time: Long, syncWithAudio: Boolean) {
        val length = activity.service?.length ?: return
        if (!DualViewTimeline.canSplit(length, time)) {
            Toast.makeText(activity, R.string.dual_view_cant_split, Toast.LENGTH_SHORT).show()
            return
        }
        val timeline = DualViewTimeline(length, time, time)
        // Keep hearing what the main player shows, unless a POV was already chosen
        start(timeline, state.value?.audioPov ?: timeline.povAt(currentMainTime()))
        if (syncWithAudio) runAudioSync(AudioSync.Mode.WHOLE)
    }

    /**
     * Match the audio around the current moment to refine the offset
     */
    fun syncWithAudio() {
        if (isActive) runAudioSync(AudioSync.Mode.AROUND)
    }

    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        setStatus(null)
    }

    /**
     * Moves the moment shown in POV 2 by [frames] frames of the video
     */
    fun nudge(frames: Int) {
        val current = state.value ?: return
        val step = if (frameDuration > 0.0) frameDuration else DEFAULT_FRAME_MS
        // Count in whole frames from where the nudges started, so they don't drift by rounding
        if (current.timeline.offset != nudgeBase + (nudges * step).roundToLong()) {
            nudgeBase = current.timeline.offset
            nudges = 0
        }
        nudges += frames
        update(current.copy(timeline = current.timeline.withOffset(nudgeBase + (nudges * step).roundToLong())))
    }

    /**
     * Duration of a frame of the playing video in ms, 0 if unknown
     */
    private fun videoFrameDuration(): Double {
        val track = activity.service?.currentVideoTrack
        val rate = track?.getFrameRateNum() ?: 0
        val scale = track?.getFrameRateDen() ?: 0
        if (rate <= 0 || scale <= 0) return 0.0
        return (1000.0 * scale / rate).takeIf { it in MIN_FRAME_MS..MAX_FRAME_MS } ?: 0.0
    }

    /**
     * Where to seek the main player to show [time]: while paused with dual view on, the middle of
     * its frame, so both views show whole, matching frames
     */
    fun mainSeekTarget(time: Long): Long {
        if (!isActive || activity.service?.isPlaying != false) return time
        return SyncController.midFrame(time, frameDuration).coerceIn(0L, (activity.service?.length ?: Long.MAX_VALUE) - 1)
    }

    fun setAudioPov(pov: Int) {
        val current = state.value ?: return
        if (current.audioPov != pov) update(current.copy(audioPov = pov))
    }

    /**
     * Shows the window's POV in the main player and the other way round
     */
    fun swap() {
        val current = state.value ?: return
        val counterpart = current.timeline.counterpart(currentMainTime())
        if (counterpart.state != CounterpartState.PLAYING) {
            activity.overlayDelegate.showInfo(activity.getString(R.string.dual_view_no_footage, counterpart.pov), 2000)
            return
        }
        // The window follows the main player's seek to the moment it showed
        activity.seek(counterpart.time, fromUser = true, fast = false)
    }

    fun close() {
        teardown()
        state.value = null
        restoreOnPlaying = null
    }

    fun setVolume(volume: Int) {
        this.volume = volume
        applyVolumes()
    }

    /**
     * @param detailed with advice, for the panel rather than the window
     */
    fun statusText(value: Status?, detailed: Boolean = false): String? = when (value) {
        null -> null
        is Status.Reading -> if (value.progress >= 1F) activity.getString(R.string.dual_view_matching_audio)
        else activity.getString(R.string.dual_view_reading_audio, (value.progress * 100).toInt())
        is Status.Synced -> activity.getString(if (detailed) R.string.dual_view_synced_hint else R.string.dual_view_synced, formatShift(value.shift))
        Status.NoMatch -> activity.getString(R.string.dual_view_no_match)
        Status.Unreadable -> activity.getString(R.string.dual_view_unreadable)
        Status.TooShort -> activity.getString(R.string.dual_view_too_short)
    }

    /**
     * The main player's current time, as precisely as known
     */
    fun currentMainTime(): Long {
        sync?.mainTime()?.takeIf { it >= 0 }?.let { return it }
        val reported = reportedMainTime()
        if (seekTarget >= 0 && reported == seekReported) return seekTarget
        seekTarget = -1L
        return reported
    }

    // Player hooks

    /**
     * The main player is about to seek to [time] from the player UI
     */
    fun onMainSeek(time: Long) {
        seekTarget = time
        seekReported = reportedMainTime()
        sync?.onMainSeek(time)
    }

    fun onMainPlayerEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                val location = activity.service?.currentMediaWrapper?.uri
                if (state.value?.location?.let { it != location } == true) close()
                if (restoreOnPlaying?.location?.let { it != location } == true) restoreOnPlaying = null
            }
            // VLC may have recreated its player, with the default volume
            MediaPlayer.Event.Playing -> {
                alignedAt = -1L
                applyVolumes()
            }
            MediaPlayer.Event.Paused -> alignPausedMain()
            MediaPlayer.Event.EndReached, MediaPlayer.Event.EncounteredError -> close()
        }
    }

    /**
     * The main player started playing: bring dual view back if it was put away
     */
    fun onPlaying() {
        val saved = restoreOnPlaying ?: return
        if (activity.isInPictureInPictureMode) return
        restoreOnPlaying = null
        if (activity.service?.currentMediaWrapper?.uri != saved.location || !isAvailable()) return
        start(saved.timeline, saved.audioPov)
    }

    /**
     * The player leaves the screen: free the second player until it comes back
     */
    fun onStop() {
        val current = state.value ?: return
        teardown()
        state.value = null
        restoreOnPlaying = current
    }

    fun onPictureInPictureModeChanged(inPictureInPicture: Boolean) {
        if (inPictureInPicture) onStop() else if (activity.service?.isPlaying == true) onPlaying()
    }

    // Sync callbacks

    override fun onCounterpartChanged(counterpart: Counterpart) {
        val current = state.value ?: return
        counterpartState = counterpart.state
        if (counterpart.pov != current.windowPov) state.value = current.copy(windowPov = counterpart.pov)
        updateWindow()
        applyVolumes()
    }

    override fun onMainReachedEnd(pov: Int) {
        val service = activity.service ?: return
        if (!isActive || !service.isPlaying) return
        service.pause()
        activity.overlayDelegate.showInfo(activity.getString(R.string.dual_view_end_of_pov, pov), 3000)
    }

    // Window callbacks

    override fun onSwap() = swap()

    override fun onAudioFromWindow() {
        state.value?.let { setAudioPov(it.windowPov) }
    }

    override fun onClose() = close()

    override fun isLocked() = activity.isLocked

    override fun onGeometryChanged(geometry: DualViewStore.Geometry) {
        store.geometry = geometry
    }

    private fun start(timeline: DualViewTimeline, audioPov: Int) {
        val service = activity.service ?: return
        val location = service.currentMediaWrapper?.uri ?: return
        val playbackUri = service.mediaplayer.media?.uri ?: location
        val window = window ?: inflateWindow() ?: return
        val mainTime = currentMainTime()
        val wasActive = isActive
        teardown()
        if (!wasActive) volume = service.volume
        val counterpart = timeline.counterpart(mainTime)
        // The player takes a moment to open: aim a bit ahead when the main player is playing
        val startTime = if (counterpart.state == CounterpartState.PLAYING && service.isPlaying) counterpart.time + SyncController.OPEN_LEAD else counterpart.time
        var created: DualViewPlayer? = null
        val newPlayer = DualViewPlayer(activity, playbackUri, startTime) { event -> if (created != null && created === player) onPlayerEvent(event) }
        created = newPlayer
        window.show(store.geometry)
        newPlayer.attach(window.videoLayout)
        newPlayer.play()
        player = newPlayer
        frameDuration = videoFrameDuration()
        val newSync = DualViewSync({ activity.service?.mediaplayer }, newPlayer, timeline, audioPov, frameDuration, this)
        sync = newSync
        counterpartState = counterpart.state
        val newState = State(location, playbackUri, timeline, audioPov, counterpart.pov)
        state.value = newState
        updateWindow()
        applyVolumes()
        newSync.start()
        save(newState)
        if (!service.isPlaying) alignPausedMain()
    }

    /**
     * Right after playback starts or seeks, VLC 3 reports a time (the one its seek bar shows) up
     * to its caching delay ahead of the picture. Once paused, show exactly the moment it reports,
     * so both views show matching frames and nudging the offset compares the right ones.
     */
    private fun alignPausedMain() {
        val service = activity.service ?: return
        if (!isActive || service.isPlaying || !service.isSeekable) return
        val reported = currentMainTime()
        if (reported < 0) return
        val time = mainSeekTarget(reported)
        if (time == alignedAt) return
        alignedAt = time
        activity.seek(time, fromUser = false, fast = false)
    }

    /**
     * Stops the second player and gives the main player its sound back
     */
    private fun teardown() {
        syncJob?.cancel()
        syncJob = null
        setStatus(null)
        sync?.release()
        sync = null
        player?.release()
        player = null
        window?.hide()
        if (isActive) activity.service?.setVolume(volume)
    }

    private fun inflateWindow(): DualViewWindow? {
        val inflated = activity.findViewById<ViewStubCompat>(R.id.dual_view_stub)?.inflate() as? DualViewWindow
                ?: activity.findViewById(R.id.dual_view_window)
                ?: return null
        inflated.callback = this
        window = inflated
        return inflated
    }

    private fun onPlayerEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Vout, MediaPlayer.Event.Playing -> player?.displayAspect?.let { window?.setAspect(it) }
            MediaPlayer.Event.EndReached -> sync?.onPlayerStopped()
            MediaPlayer.Event.EncounteredError -> {
                Toast.makeText(activity, R.string.dual_view_player_error, Toast.LENGTH_LONG).show()
                close()
            }
        }
    }

    private fun update(newState: State) {
        state.value = newState
        sync?.setTimeline(newState.timeline)
        sync?.setAudioPov(newState.audioPov)
        updateWindow()
        applyVolumes()
        save(newState)
    }

    /**
     * Only the player showing the chosen POV is heard
     */
    private fun applyVolumes() {
        val current = state.value ?: return
        val mainAudible = current.windowPov != current.audioPov
        activity.service?.setVolume(if (mainAudible) volume else 0)
        player?.setVolume(if (mainAudible) 0 else volume)
    }

    private fun updateWindow() {
        val current = state.value ?: return
        val window = window ?: return
        window.setLabel(current.windowPov, current.windowPov == current.audioPov)
        window.setMessage(when (counterpartState) {
            CounterpartState.NOT_STARTED -> activity.getString(R.string.dual_view_not_started, current.windowPov)
            CounterpartState.ENDED -> activity.getString(R.string.dual_view_ended, current.windowPov)
            CounterpartState.PLAYING -> null
        })
    }

    private fun runAudioSync(mode: AudioSync.Mode) {
        val current = state.value ?: return
        syncJob?.cancel()
        val timeline = current.timeline
        val mainTime = currentMainTime()
        val pov1Time = if (timeline.povAt(mainTime) == 1) mainTime else mainTime - timeline.offset
        val source = AudioSync.Source(current.playbackUri, current.location, "${DualViewStore.hash(current.location)}_${timeline.length}")
        setStatus(Status.Reading(0F))
        syncJob = activity.lifecycleScope.launch {
            // The player only keeps the screen on while playing: don't let it go off while the user waits
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val result = try {
                AudioSync(activity.applicationContext).findOffset(source, timeline, mode, pov1Time) { progress ->
                    activity.runOnUiThread { if (status.value is Status.Reading) setStatus(Status.Reading(progress)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Audio sync failed", e)
                AudioSync.Result.Unreadable
            } finally {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val latest = state.value
            if (latest == null || latest.location != current.location) return@launch
            syncJob = null
            setStatus(when (result) {
                is AudioSync.Result.Found -> {
                    update(latest.copy(timeline = latest.timeline.withOffset(result.offset)))
                    Status.Synced(result.offset - latest.timeline.split)
                }
                AudioSync.Result.NoMatch -> Status.NoMatch
                AudioSync.Result.TooShort -> Status.TooShort
                AudioSync.Result.Unreadable -> Status.Unreadable
            })
        }
    }

    private fun setStatus(value: Status?) {
        status.value = value
        val window = window ?: return
        window.removeCallbacks(hideStatus)
        window.setStatus(statusText(value))
        // Results only show for a moment in the window, the panel keeps them
        if (value != null && value !is Status.Reading) window.postDelayed(hideStatus, STATUS_TIMEOUT)
    }

    private fun save(current: State) {
        store.save(current.location, DualViewStore.Saved(current.timeline.split, current.timeline.offset, current.audioPov, current.timeline.length))
    }

    private fun reportedMainTime() = try {
        activity.service?.mediaplayer?.time ?: -1L
    } catch (e: IllegalStateException) {
        -1L
    }

    companion object {
        private const val TAG = "VLC/DualViewDelegate"
        private const val DEFAULT_FRAME_MS = 40.0
        private const val MIN_FRAME_MS = 8.0
        private const val MAX_FRAME_MS = 100.0
        private const val STATUS_TIMEOUT = 4_000L

        fun formatShift(shift: Long): String = String.format(Locale.getDefault(), "%+.3f s", shift / 1000.0)
    }
}
