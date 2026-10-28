/*
 * ************************************************************************
 *  SyncController.kt
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

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Estimates a player's current time from the times VLC reports.
 *
 * VLC 3 reports the time only every 250 ms, and not at all while it is paused or buffering after a
 * seek. Between reports the time is extrapolated from the moment the last one was seen.
 */
class PlayerClock {
    // Last known time, and when it was valid
    private var known = -1L
    private var knownAt = 0L
    // Last value VLC reported
    private var lastReported = -1L
    // Don't extrapolate until VLC reports a new value, e.g. while a seek completes
    private var holding = false
    private var wasPlaying = false

    /**
     * Records the time VLC reports at [now] (ms, monotonic)
     *
     * @return true if the time jumped: something else than playback moved it
     */
    fun update(time: Long, playing: Boolean, rate: Float, now: Long): Boolean {
        val wasPlaying = this.wasPlaying
        this.wasPlaying = playing
        if (time < 0) {
            reset()
            return false
        }
        if (time == lastReported) {
            // VLC doesn't report the time it paused at: keep the extrapolated one
            if (wasPlaying && !playing && known >= 0) {
                known = estimate(now, true, rate)
                knownAt = now
                holding = false
            }
            return false
        }
        val jumped = known >= 0 && abs(time - estimate(now, playing, rate)) > if (playing) JUMP_WHILE_PLAYING else JUMP_WHILE_PAUSED
        lastReported = time
        known = time
        knownAt = now
        holding = false
        return jumped
    }

    /**
     * Sets the time to a known value, e.g. the target of a seek, until VLC reports a new one
     */
    fun set(time: Long, now: Long) {
        known = time
        knownAt = now
        holding = true
    }

    fun estimate(now: Long, playing: Boolean, rate: Float) = when {
        known < 0 -> -1L
        playing && !holding -> known + ((now - knownAt) * rate).toLong()
        else -> known
    }

    fun reset() {
        known = -1L
        lastReported = -1L
        holding = false
    }

    companion object {
        const val JUMP_WHILE_PLAYING = 700L
        const val JUMP_WHILE_PAUSED = 30L
    }
}

/**
 * Decides how to keep the second player on the moment that matches the main player.
 *
 * It only holds filtering and decision state: callers feed it time estimates every
 * [TICK] ms and apply the [Action]s it returns, so it can be tested with simulated clocks.
 */
class SyncController {

    var timeline: DualViewTimeline? = null
    var audioPov = 1

    /**
     * Duration of a frame of the video in ms, 0 if unknown: while paused, the window is aimed at
     * the middle of the frame to show, see [midFrame]
     */
    var frameDuration = 0.0

    /**
     * @param now monotonic time, in ms
     * @param mainTime estimated time of the main player, -1 if unknown
     * @param mainSeekTarget when the main player was just seeked from the UI, the exact target, else -1
     * @param mainJumped the main player's time jumped without a known seek (notification, remote...)
     * @param slaveTime estimated time of the second player, -1 if unknown
     */
    data class Input(
            val now: Long,
            val mainTime: Long,
            val mainPlaying: Boolean,
            val mainRate: Float,
            val mainSeekTarget: Long = -1L,
            val mainJumped: Boolean = false,
            val slaveTime: Long,
            val slavePlaying: Boolean,
            val slaveRate: Float
    )

    sealed class Action {
        data class Seek(val time: Long) : Action()
        data class SetRate(val rate: Float) : Action()
        object Play : Action()
        object Pause : Action()

        /**
         * The main player reached the end of [pov] while playing
         */
        data class PauseMain(val pov: Int) : Action()

        /**
         * The second player now shows another POV, or that POV gained or lost footage
         */
        data class CounterpartChanged(val counterpart: Counterpart) : Action()
    }

    private var lastCounterpart: Counterpart? = null
    private var lastMainTime = -1L
    private var lastSeekAt = Long.MIN_VALUE / 2
    private var settleUntil = 0L
    private var filteredError = 0F
    private var hasError = false
    private var seekLead = OPEN_LEAD
    private var learnLead = false
    // The second player is paused on the frame matching the main player: resuming needs no seek
    private var heldInStep = false
    // Time the second player was parked at when its POV has no footage
    private var parkedAt = -1L
    private var commandedPlaying = false
    private var commandUntil = 0L
    // The offset changed: a paused second player has to show the new matching frame
    private var realign = false

    /**
     * The bounds, the cuts or the offsets changed
     */
    fun onTimelineChanged() {
        realign = true
        // Moving the split under the main player isn't playback reaching it
        lastMainTime = -1L
    }

    /**
     * Forget the second player's state, e.g. when it was (re)created at [now]
     */
    fun reset(now: Long) {
        lastCounterpart = null
        lastMainTime = -1L
        resetFilter()
        heldInStep = false
        parkedAt = -1L
        commandUntil = 0L
        seekLead = OPEN_LEAD
        learnLead = true
        settleUntil = now + SETTLE_AT_START
    }

    /**
     * The second player stopped by itself (end of the file reached)
     */
    fun onSlaveStopped() {
        heldInStep = false
        parkedAt = -1L
        commandUntil = 0L
    }

    fun update(input: Input): List<Action> {
        val timeline = timeline ?: return emptyList()
        if (input.mainTime < 0 && input.mainSeekTarget < 0) return emptyList()
        val actions = ArrayList<Action>(3)
        val mainTime = if (input.mainSeekTarget >= 0) input.mainSeekTarget else input.mainTime
        val counterpart = timeline.counterpart(mainTime)
        val previous = lastCounterpart
        if (previous == null || previous.pov != counterpart.pov || previous.state != counterpart.state)
            actions.add(Action.CounterpartChanged(counterpart))
        lastCounterpart = counterpart

        // Playback reached the end of POV 1: the other POV would just start over. Not right after
        // a seek, which can land a bit before its target and play on into POV 2
        if (input.mainSeekTarget >= 0 || input.mainJumped) lastSeekAt = input.now
        val guard = timeline.split - END_GUARD
        if (input.mainPlaying && input.mainSeekTarget < 0 && !input.mainJumped && input.now - lastSeekAt > SEEK_QUIET
                && lastMainTime in 0 until guard && mainTime >= guard)
            actions.add(Action.PauseMain(1))
        lastMainTime = mainTime

        val slavePlaying = if (input.now < commandUntil) commandedPlaying else input.slavePlaying
        val inRange = counterpart.state == CounterpartState.PLAYING
        // Playback crossed a cut: the matching moment jumped
        val crossedCut = previous != null && previous.segment != counterpart.segment && previous.pov == counterpart.pov && !realign
        // The timeline was edited while playing (offset dragged): don't steer slowly over a big change
        val movedFar = realign && input.slaveTime >= 0 && abs(input.slaveTime - counterpart.time) > REALIGN_SEEK
        when {
            input.mainSeekTarget >= 0 -> {
                // Both players seek at the same time and buffer alike: no lead
                if (!inRange || !input.mainPlaying) {
                    if (slavePlaying) actions.add(command(false, input.now))
                    parkedAt = if (inRange) -1L else counterpart.time
                    heldInStep = inRange
                }
                actions.add(Action.Seek(if (inRange && !input.mainPlaying) midFrame(counterpart.time, frameDuration) else counterpart.time))
                if (input.slaveRate != input.mainRate) actions.add(Action.SetRate(input.mainRate))
                learnLead = false
                settle(input.now, SETTLE_AFTER_MAIN_SEEK)
            }
            !inRange -> {
                if (slavePlaying) actions.add(command(false, input.now))
                if (parkedAt < 0 || abs(parkedAt - counterpart.time) > PARK_TOLERANCE) {
                    actions.add(Action.Seek(counterpart.time))
                    parkedAt = counterpart.time
                }
                heldInStep = false
                resetFilter()
            }
            !input.mainPlaying -> {
                if (slavePlaying) {
                    actions.add(command(false, input.now))
                    heldInStep = parkedAt < 0
                }
                if (input.mainJumped || realign) {
                    actions.add(Action.Seek(midFrame(counterpart.time, frameDuration)))
                    heldInStep = true
                }
                parkedAt = -1L
                resetFilter()
            }
            !slavePlaying -> {
                if (heldInStep) {
                    settle(input.now, SETTLE_AFTER_RESUME)
                } else {
                    actions.add(Action.Seek(counterpart.time + lead(input.mainRate)))
                    learnLead = true
                    settle(input.now, SETTLE_AFTER_SEEK)
                }
                if (input.slaveRate != input.mainRate) actions.add(Action.SetRate(input.mainRate))
                actions.add(command(true, input.now))
                heldInStep = false
                parkedAt = -1L
            }
            input.mainJumped || crossedCut || movedFar -> {
                actions.add(Action.Seek(counterpart.time + lead(input.mainRate)))
                learnLead = true
                settle(input.now, SETTLE_AFTER_SEEK)
            }
            input.now < settleUntil || input.slaveTime < 0 -> {}
            else -> steer(input, counterpart, actions)
        }
        realign = false
        return actions
    }

    private fun steer(input: Input, counterpart: Counterpart, actions: MutableList<Action>) {
        val error = (input.slaveTime - counterpart.time).toFloat()
        if (learnLead) {
            // The second player landed this far from the target: aim better next time.
            // A bigger miss isn't about the seek time (e.g. the main player stalled meanwhile)
            if (abs(error) < MAX_LEAD) seekLead = (seekLead - error.toLong()).coerceIn(0L, MAX_LEAD)
            learnLead = false
        }
        filteredError = if (hasError) filteredError + ALPHA * (error - filteredError) else error
        hasError = true
        val absError = abs(filteredError)
        val maxAdjust = if (counterpart.pov == audioPov) MAX_ADJUST_AUDIBLE else MAX_ADJUST
        when {
            absError > SEEK_THRESHOLD -> {
                actions.add(Action.Seek(counterpart.time + lead(input.mainRate)))
                if (input.slaveRate != input.mainRate) actions.add(Action.SetRate(input.mainRate))
                learnLead = true
                settle(input.now, SETTLE_AFTER_SEEK)
            }
            absError > RATE_THRESHOLD -> {
                val rate = input.mainRate * (1F - (filteredError / RATE_GAIN).coerceIn(-maxAdjust, maxAdjust))
                if (abs(rate - input.slaveRate) > RATE_EPSILON) actions.add(Action.SetRate(rate))
            }
            absError < IN_STEP -> if (abs(input.slaveRate - input.mainRate) > RATE_EPSILON) actions.add(Action.SetRate(input.mainRate))
            // In between: keep the current correction, unless the main player's speed changed
            abs(input.slaveRate - input.mainRate) > input.mainRate * maxAdjust + RATE_EPSILON -> actions.add(Action.SetRate(input.mainRate))
        }
    }

    private fun lead(rate: Float) = (seekLead * rate).toLong()

    private fun command(play: Boolean, now: Long): Action {
        commandedPlaying = play
        commandUntil = now + COMMAND_GRACE
        return if (play) Action.Play else Action.Pause
    }

    private fun settle(now: Long, duration: Long) {
        settleUntil = now + duration
        resetFilter()
    }

    private fun resetFilter() {
        filteredError = 0F
        hasError = false
    }

    companion object {
        /**
         * The middle of the frame showing at [time], frames lasting [frameDuration] ms from 0
         * ([time] itself if 0). A paused player shows the frame on screen a few ms before the
         * time it is seeked to, the previous one near a frame boundary: aiming at the middle shows
         * the intended frame, and one frame further is exactly one frame duration away.
         */
        fun midFrame(time: Long, frameDuration: Double): Long =
                if (frameDuration <= 0.0) time else ((floor(time / frameDuration + 1e-6) + 0.5) * frameDuration).roundToLong()

        const val TICK = 100L
        const val END_GUARD = 150L
        const val SEEK_QUIET = 1_000L
        const val OPEN_LEAD = 600L
        const val MAX_LEAD = 1_500L
        const val SETTLE_AT_START = 1_500L
        const val SETTLE_AFTER_MAIN_SEEK = 1_000L
        const val SETTLE_AFTER_SEEK = 800L
        const val SETTLE_AFTER_RESUME = 500L
        const val COMMAND_GRACE = 500L
        const val PARK_TOLERANCE = 40L
        const val REALIGN_SEEK = 250L
        const val SEEK_THRESHOLD = 400F
        const val RATE_THRESHOLD = 25F
        const val IN_STEP = 10F
        const val ALPHA = 0.3F
        const val RATE_GAIN = 1_000F
        const val MAX_ADJUST = 0.15F
        const val MAX_ADJUST_AUDIBLE = 0.05F
        const val RATE_EPSILON = 0.004F
    }
}
