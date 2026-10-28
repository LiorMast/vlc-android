package org.videolan.vlc.gui.video.dualview

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DualViewSourceTest {

    @Test
    fun withLane_tagsProxiesOnThisDeviceOnly() {
        assertEquals("http://localhost:41234/My%20Video.mkv?lane=dv-window", DualViewSource.withLane("http://localhost:41234/My%20Video.mkv", "dv-window"))
        assertEquals("http://127.0.0.1:8080/v.mp4?a=1&lane=dv-audio-2", DualViewSource.withLane("http://127.0.0.1:8080/v.mp4?a=1", DualViewSource.audioLane(2)))
        assertEquals("http://server.lan/v.mp4", DualViewSource.withLane("http://server.lan/v.mp4", "dv-window"))
        assertEquals("smb://nas/share/v.mkv", DualViewSource.withLane("smb://nas/share/v.mkv", "dv-window"))
        assertEquals("content://org.courville.nova.provider/external_files/v.mkv", DualViewSource.withLane("content://org.courville.nova.provider/external_files/v.mkv", "dv-window"))
    }

    @Test
    fun stableKey_ignoresTheProxyPort() {
        assertEquals(DualViewSource.stableKey("http://localhost:41234/My%20Video.mkv"), DualViewSource.stableKey("http://localhost:39999/My%20Video.mkv"))
        assertEquals("smb://nas/share/v.mkv", DualViewSource.stableKey("smb://nas/share/v.mkv"))
    }
}
