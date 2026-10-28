package org.videolan.vlc.gui.video.dualview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class SavedTimelineCodecTest {

    private val timeline = DualViewTimeline(100_000, 2_000, 50_000, 98_000, listOf(Segment(2_000, 51_000), Segment(20_000, 54_000)))

    @Test
    fun roundTrip() {
        val saved = SavedTimeline(timeline, 2)
        val encoded = SavedTimelineCodec.encode(saved, 123L)
        assertEquals(saved, SavedTimelineCodec.decode(encoded, 100_000))
        assertEquals(123L, SavedTimelineCodec.usedAt(encoded))
        // Same video, length reported a bit differently
        assertEquals(saved.timeline.segments, SavedTimelineCodec.decode(encoded, 100_400)?.timeline?.segments)
    }

    @Test
    fun readsTheSingleOffsetFormat() {
        val saved = SavedTimelineCodec.decode("50000;51234;1;100000;987", 100_000)
        assertEquals(SavedTimeline(DualViewTimeline.of(100_000, 50_000, 51_234), 1), saved)
        assertEquals(987L, SavedTimelineCodec.usedAt("50000;51234;1;100000;987"))
    }

    @Test
    fun dropsOtherVideosAndGarbage() {
        val encoded = SavedTimelineCodec.encode(SavedTimeline(timeline, 1), 0L)
        assertNull(SavedTimelineCodec.decode(encoded, 200_000))
        assertNull(SavedTimelineCodec.decode("v2;nonsense", 100_000))
        assertNull(SavedTimelineCodec.decode("", 100_000))
        assertNull(SavedTimelineCodec.decode("1000;1000;1;100000;0", 100_000))
    }

    @Test
    fun keepsEveryCutAndBothBounds() {
        val cuts = DualViewTimeline(600_000, 12_000, 300_000, 587_000,
                listOf(Segment(12_000, 301_000), Segment(80_500, 304_250), Segment(150_000, 299_990), Segment(222_222, 305_000)))
        val saved = SavedTimeline(cuts, 1)
        assertEquals(saved, SavedTimelineCodec.decode(SavedTimelineCodec.encode(saved, 1L), 600_000))
    }
}
