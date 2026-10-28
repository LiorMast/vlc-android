/*
 * ************************************************************************
 *  DualViewPlayer.kt
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

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.videolan.libvlc.FactoryManager
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMediaFactory
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.resources.VLCInstance
import org.videolan.resources.VLCOptions
import org.videolan.tools.AppScope
import org.videolan.tools.Settings
import org.videolan.vlc.getVideoDisplayAspect
import java.util.Locale

/**
 * The second LibVLC player, showing the other POV of the video in the dual view window
 *
 * Its controls can be called from any thread; [attach] and [release] from the main one.
 *
 * @param uri the address the main player plays
 * @param startTime where to start, in ms
 * @param onEvent receives the player's events, on the main thread
 */
class DualViewPlayer(context: Context, uri: Uri, startTime: Long, onEvent: (MediaPlayer.Event) -> Unit) {

    private val player = MediaPlayer(VLCInstance.getInstance(context))

    @Volatile
    var isReleased = false
        private set

    // After the end of the file VLC has to start over: seek once it plays again
    @Volatile
    private var ended = false
    @Volatile
    private var seekAfterRestart = -1L

    init {
        val settings = Settings.getInstance(context)
        val media = mediaFactory.getFromUri(VLCInstance.getInstance(context), uri)
        media.addOption(String.format(Locale.US, ":start-time=%.3f", startTime / 1000.0))
        media.addOption(":no-spu")
        VLCOptions.setMediaOptions(media, context, MediaWrapper.MEDIA_VIDEO, false)
        player.setAudioDigitalOutputEnabled(false)
        VLCOptions.getAout(settings)?.let { player.setAudioOutput(it) }
        player.volume = 0
        player.media = media
        media.release()
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.EndReached, MediaPlayer.Event.Stopped -> ended = true
                MediaPlayer.Event.Playing -> if (ended) {
                    ended = false
                    val time = seekAfterRestart
                    seekAfterRestart = -1L
                    if (time >= 0) seek(time)
                }
            }
            onEvent(event)
        }
    }

    fun attach(layout: VLCVideoLayout) {
        player.attachViews(layout, null, false, true)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
    }

    fun play() = guarded { player.play() }

    fun pause() = guarded { player.pause() }

    fun seek(time: Long) {
        if (ended) seekAfterRestart = time else guarded { player.setTime(time, false) }
    }

    fun setRate(rate: Float) = guarded { player.rate = rate }

    fun setVolume(volume: Int) = guarded { player.volume = volume }

    fun setAudioDelay(delay: Long) = guarded { if (player.audioDelay != delay) player.setAudioDelay(delay) }

    val time: Long
        get() = guarded(-1L) { player.time }

    val isPlaying: Boolean
        get() = guarded(false) { player.isPlaying }

    val rate: Float
        get() = guarded(1F) { player.rate }

    /**
     * Width / height of the picture as it is displayed, or null before the video is known
     */
    val displayAspect: Float?
        get() = guarded(null) { player.getVideoDisplayAspect() }

    fun release() {
        if (isReleased) return
        isReleased = true
        player.setEventListener(null)
        player.detachViews()
        AppScope.launch(Dispatchers.IO) { player.release() }
    }

    private fun guarded(block: () -> Unit) {
        guarded(Unit, block)
    }

    private fun <T> guarded(fallback: T, block: () -> T): T {
        if (isReleased) return fallback
        return try {
            block()
        } catch (e: IllegalStateException) {
            // Released meanwhile from another thread
            fallback
        }
    }

    companion object {
        private val mediaFactory = FactoryManager.getFactory(IMediaFactory.factoryId) as IMediaFactory
    }
}
