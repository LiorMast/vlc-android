/*
 * ************************************************************************
 *  DualViewSync.kt
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

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.videolan.libvlc.MediaPlayer
import org.videolan.vlc.BuildConfig

private const val TAG = "VLC/DualView"

/**
 * Keeps the second player on the moment matching the main player
 *
 * A dedicated thread reads both players' times every [SAMPLE_INTERVAL] ms, so it can tell
 * precisely when VLC reports a new one, and lets [SyncController] decide every
 * [SyncController.TICK] ms.
 *
 * @param mainPlayer the main player, read from the sync thread: it may change or be released
 */
class DualViewSync(
        private val mainPlayer: () -> MediaPlayer?,
        private val player: DualViewPlayer,
        timeline: DualViewTimeline,
        audioPov: Int,
        frameDuration: Double,
        private val listener: Listener
) {

    /**
     * Called on the main thread
     */
    interface Listener {
        fun onCounterpartChanged(counterpart: Counterpart)
        fun onMainReachedEnd(pov: Int)
    }

    private val thread = HandlerThread("VLC/DualViewSync").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val controller = SyncController().apply {
        this.timeline = timeline
        this.audioPov = audioPov
        this.frameDuration = frameDuration
    }
    private val mainClock = PlayerClock()
    private val playerClock = PlayerClock()
    private var mainSeekTarget = -1L
    private var mainJumped = false
    private var samples = 0
    private var lastLog = 0L

    // Latest main player estimate, for the main thread
    @Volatile
    private var mainEstimate = -1L
    @Volatile
    private var mainEstimateAt = 0L
    @Volatile
    private var mainPlaying = false
    @Volatile
    private var mainRate = 1F

    private val sampler = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, SAMPLE_INTERVAL)
        }
    }

    fun start() {
        handler.post {
            controller.reset(SystemClock.elapsedRealtime())
            sampler.run()
        }
    }

    fun setTimeline(timeline: DualViewTimeline) {
        handler.post {
            controller.timeline = timeline
            controller.onTimelineChanged()
        }
    }

    fun setAudioPov(pov: Int) {
        handler.post { controller.audioPov = pov }
    }

    /**
     * The main player is being seeked to [time] from the player UI
     */
    fun onMainSeek(time: Long) {
        handler.post {
            mainSeekTarget = time
            mainClock.set(time, SystemClock.elapsedRealtime())
        }
    }

    /**
     * The second player reached the end of the file
     */
    fun onPlayerStopped() {
        handler.post {
            controller.onSlaveStopped()
            playerClock.reset()
        }
    }

    /**
     * Best estimate of the main player's time, from any thread
     */
    fun mainTime(): Long {
        val estimate = mainEstimate
        if (estimate < 0 || !mainPlaying) return estimate
        return estimate + ((SystemClock.elapsedRealtime() - mainEstimateAt) * mainRate).toLong()
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        handler.post { thread.quit() }
    }

    @Volatile
    private var released = false

    private fun notify(block: () -> Unit) {
        mainHandler.post { if (!released) block() }
    }

    private fun sample() {
        val now = SystemClock.elapsedRealtime()
        val main = mainPlayer() ?: return
        val time: Long
        val playing: Boolean
        val rate: Float
        try {
            time = main.time
            playing = main.isPlaying
            rate = main.rate
        } catch (e: IllegalStateException) {
            return // The main player was released meanwhile
        }
        if (mainClock.update(time, playing, rate, now)) mainJumped = true
        val playerPlaying = player.isPlaying
        val playerRate = player.rate
        playerClock.update(player.time, playerPlaying, playerRate, now)
        val estimate = mainClock.estimate(now, playing, rate)
        mainEstimate = estimate
        mainEstimateAt = now
        mainPlaying = playing
        mainRate = rate
        if (++samples % TICKS_PER_DECISION != 0 && mainSeekTarget < 0) return

        val input = SyncController.Input(
                now = now,
                mainTime = estimate,
                mainPlaying = playing,
                mainRate = rate,
                mainSeekTarget = mainSeekTarget,
                mainJumped = mainJumped && mainSeekTarget < 0,
                slaveTime = playerClock.estimate(now, playerPlaying, playerRate),
                slavePlaying = playerPlaying,
                slaveRate = playerRate
        )
        mainSeekTarget = -1L
        mainJumped = false
        for (action in controller.update(input)) apply(action, now)
        if (samples % (TICKS_PER_DECISION * AUDIO_DELAY_TICKS) == 0) try {
            player.setAudioDelay(main.audioDelay)
        } catch (ignored: IllegalStateException) {
        }
        if (BuildConfig.DEBUG && now - lastLog >= LOG_INTERVAL) {
            lastLog = now
            val target = controller.timeline?.counterpart(input.mainTime)
            Log.d(TAG, "main ${input.mainTime} playing=$playing, window ${input.slaveTime} playing=$playerPlaying rate=$playerRate, error ${if (target != null && input.slaveTime >= 0) input.slaveTime - target.time else null} ms")
        }
    }

    private fun apply(action: SyncController.Action, now: Long) {
        when (action) {
            is SyncController.Action.Seek -> {
                player.seek(action.time)
                playerClock.set(action.time, now)
            }
            is SyncController.Action.SetRate -> player.setRate(action.rate)
            SyncController.Action.Play -> player.play()
            SyncController.Action.Pause -> player.pause()
            is SyncController.Action.PauseMain -> notify { listener.onMainReachedEnd(action.pov) }
            is SyncController.Action.CounterpartChanged -> notify { listener.onCounterpartChanged(action.counterpart) }
        }
    }

    companion object {
        const val SAMPLE_INTERVAL = 10L
        // SyncController.TICK / SAMPLE_INTERVAL
        private const val TICKS_PER_DECISION = 10
        private const val AUDIO_DELAY_TICKS = 10
        private const val LOG_INTERVAL = 2_000L
    }
}
