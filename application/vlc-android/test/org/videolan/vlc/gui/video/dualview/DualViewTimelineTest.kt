package org.videolan.vlc.gui.video.dualview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DualViewTimelineTest {

    @Test
    fun povAt_splitsAtTheSplitPoint() {
        val timeline = DualViewTimeline(100_000, 50_000, 50_000)
        assertEquals(1, timeline.povAt(0))
        assertEquals(1, timeline.povAt(49_999))
        assertEquals(2, timeline.povAt(50_000))
        assertEquals(2, timeline.povAt(99_999))
    }

    @Test
    fun counterpart_mapsBothWays() {
        val timeline = DualViewTimeline(100_000, 50_000, 51_234)
        assertEquals(Counterpart(2, 61_234, CounterpartState.PLAYING), timeline.counterpart(10_000))
        assertEquals(Counterpart(1, 10_000, CounterpartState.PLAYING), timeline.counterpart(61_234))
        assertEquals(1_234L, timeline.shift)
    }

    @Test
    fun counterpart_clampsWhenTheOtherPovHasNoFootage() {
        // POV 2 started recording 2 s after POV 1
        val late = DualViewTimeline(100_000, 50_000, 48_000)
        assertEquals(Counterpart(2, 50_000, CounterpartState.NOT_STARTED), late.counterpart(1_000))
        assertEquals(CounterpartState.PLAYING, late.counterpart(2_000).state)
        // POV 2 stopped recording before POV 1: its footage ends at the end of the file
        val endedEarly = DualViewTimeline(100_000, 60_000, 52_000)
        val counterpart = endedEarly.counterpart(59_000)
        assertEquals(CounterpartState.ENDED, counterpart.state)
        assertEquals(100_000 - DualViewTimeline.EDGE_MARGIN, counterpart.time)
        // POV 1 does have footage when POV 2 starts, 8 s after it
        assertEquals(Counterpart(1, 8_000, CounterpartState.PLAYING), endedEarly.counterpart(60_000))
        // POV 2 started recording 3 s before POV 1
        val early = DualViewTimeline(100_000, 50_000, 53_000)
        assertEquals(Counterpart(1, 0, CounterpartState.NOT_STARTED), early.counterpart(51_000))
        assertEquals(Counterpart(1, 1_000, CounterpartState.PLAYING), early.counterpart(54_000))
    }

    @Test
    fun canSplit_needsTwoUsablePovs() {
        assertTrue(DualViewTimeline.canSplit(100_000, 50_000))
        assertFalse(DualViewTimeline.canSplit(100_000, 1_000))
        assertFalse(DualViewTimeline.canSplit(100_000, 99_000))
        assertFalse(DualViewTimeline.canSplit(8_000, 4_000))
    }
}
