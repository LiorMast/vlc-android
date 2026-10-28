/*
 * ************************************************************************
 *  DualViewStore.kt
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
import androidx.core.content.edit
import java.security.MessageDigest

/**
 * Dual view settings, in their own preferences file so VLC's settings export leaves them out
 */
class DualViewStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * A video's split, as last used
     */
    data class Saved(val split: Long, val offset: Long, val audioPov: Int, val length: Long)

    /**
     * Where the window sits: [x] and [y] place it between the edges of the screen (0 to 1), [size]
     * is its width as a fraction of the screen's shorter side
     */
    data class Geometry(val x: Float, val y: Float, val size: Float)

    var syncOnSplitMiddle: Boolean
        get() = prefs.getBoolean(KEY_SYNC_SPLIT_MIDDLE, true)
        set(value) = prefs.edit { putBoolean(KEY_SYNC_SPLIT_MIDDLE, value) }

    var geometry: Geometry?
        get() = prefs.getString(KEY_GEOMETRY, null)?.split(';')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 }?.let { Geometry(it[0], it[1], it[2]) }
        set(value) = prefs.edit { if (value == null) remove(KEY_GEOMETRY) else putString(KEY_GEOMETRY, "${value.x};${value.y};${value.size}") }

    /**
     * The split saved for the video at [location], if its length still matches
     */
    fun load(location: Uri, length: Long): Saved? {
        val values = prefs.getString(keyFor(location), null)?.split(';')?.mapNotNull { it.toLongOrNull() } ?: return null
        if (values.size < 4) return null
        val saved = Saved(values[0], values[1], values[2].toInt(), values[3])
        if (kotlin.math.abs(saved.length - length) > LENGTH_TOLERANCE || !DualViewTimeline.canSplit(length, saved.split)) return null
        return saved
    }

    fun save(location: Uri, saved: Saved) {
        prefs.edit {
            putString(keyFor(location), "${saved.split};${saved.offset};${saved.audioPov};${saved.length};${System.currentTimeMillis()}")
        }
        prune()
    }

    /**
     * Keeps the [MAX_SAVED] most recently used splits
     */
    private fun prune() {
        val splits = prefs.all.filterKeys { it.startsWith(SPLIT_PREFIX) }
        if (splits.size <= MAX_SAVED) return
        val oldest = splits.entries.sortedBy { (it.value as? String)?.substringAfterLast(';')?.toLongOrNull() ?: 0L }.take(splits.size - MAX_SAVED)
        prefs.edit { oldest.forEach { remove(it.key) } }
    }

    companion object {
        private const val PREFS_NAME = "dual_view"
        private const val KEY_SYNC_SPLIT_MIDDLE = "sync_split_middle"
        private const val KEY_GEOMETRY = "window_geometry"
        private const val SPLIT_PREFIX = "split_"
        private const val MAX_SAVED = 200
        private const val LENGTH_TOLERANCE = 1_000L

        private fun keyFor(location: Uri) = SPLIT_PREFIX + hash(location)

        /**
         * Short stable identifier of a video, for keys and cache file names
         */
        fun hash(location: Uri) = MessageDigest.getInstance("SHA-1").digest(location.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
