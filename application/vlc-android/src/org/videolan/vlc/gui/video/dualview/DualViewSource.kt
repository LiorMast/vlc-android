/*
 * ************************************************************************
 *  DualViewSource.kt
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

/**
 * The addresses dual view opens besides the main player's.
 *
 * Players such as Nova Video Player hand VLC their files through a proxy on this device
 * (http://localhost:<port>/...) that drops a connection when a newer one to the same file
 * arrives, unless the two name different lanes. Every extra stream dual view opens names its own.
 *
 * Works on URI strings, so it doesn't need Android.
 */
object DualViewSource {

    const val LANE_WINDOW = "dv-window"
    const val LANE_VLC_AUDIO = "dv-vlc"

    fun audioLane(slot: Int) = "dv-audio-$slot"

    private val localProxy = Regex("""^(https?)://(localhost|127\.0\.0\.1)(:\d+)?(/[^?#]*)?(\?[^#]*)?(#.*)?$""", RegexOption.IGNORE_CASE)

    fun isLocalProxy(uri: String) = localProxy.matches(uri)

    /**
     * [uri] tagged with [lane] when it points to a proxy on this device, else unchanged
     */
    fun withLane(uri: String, lane: String): String {
        val match = localProxy.matchEntire(uri) ?: return uri
        val (scheme, host, port, path, query, fragment) = match.destructured
        val parameter = "lane=$lane"
        val newQuery = if (query.length > 1) "$query&$parameter" else "?$parameter"
        return "$scheme://$host$port$path$newQuery$fragment"
    }

    /**
     * Identifies the video at [uri] across launches: proxies on this device get a new port each
     * time, so only their path counts
     */
    fun stableKey(uri: String): String {
        val match = localProxy.matchEntire(uri) ?: return uri
        return "localproxy:" + match.groupValues[4]
    }
}
