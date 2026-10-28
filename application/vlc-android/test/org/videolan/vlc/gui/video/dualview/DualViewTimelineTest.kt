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
        val timeline = DualViewTimeline.of(100_000, 50_000, 50_000)
        assertEquals(1, timeline.povAt(0))
        assertEquals(1, timeline.povAt(49_999))
        assertEquals(2, timeline.povAt(50_000))
        assertEquals(2, timeline.povAt(99_999))
    }

    @Test
    fun counterpart_mapsBothWays() {
        val timeline = DualViewTimeline.of(100_000, 50_000, 51_234)
        assertEquals(Counterpart(2, 61_234, CounterpartState.PLAYING), timeline.counterpart(10_000))
        assertEquals(Counterpart(1, 10_000, CounterpartState.PLAYING), timeline.counterpart(61_234))
        assertEquals(1_234L, timeline.offsetAt(0) - timeline.split)
    }

    @Test
    fun counterpart_clampsWhenTheOtherPovHasNoFootage() {
        // POV 2 started recording 2 s after POV 1
        val late = DualViewTimeline.of(100_000, 50_000, 48_000)
        assertEquals(Counterpart(2, 50_000, CounterpartState.NOT_STARTED), late.counterpart(1_000))
        assertEquals(CounterpartState.PLAYING, late.counterpart(2_000).state)
        // POV 2 stopped recording before POV 1: its footage ends at the end of the file
        val endedEarly = DualViewTimeline.of(100_000, 60_000, 52_000)
        val counterpart = endedEarly.counterpart(59_000)
        assertEquals(CounterpartState.ENDED, counterpart.state)
        assertEquals(100_000 - DualViewTimeline.EDGE_MARGIN, counterpart.time)
        // POV 1 does have footage when POV 2 starts, 8 s after it
        assertEquals(Counterpart(1, 8_000, CounterpartState.PLAYING), endedEarly.counterpart(60_000))
        // POV 2 started recording 3 s before POV 1
        val early = DualViewTimeline.of(100_000, 50_000, 53_000)
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

    // POV 1 is [0, 50 s), POV 2 [50 s, 100 s). 20 s into POV 1, POV 1 lost 3 s of footage: from
    // there on its moments are heard 3 s later in POV 2
    private val cut = DualViewTimeline(100_000, 0, 50_000, 100_000, listOf(Segment(0, 51_000), Segment(20_000, 54_000)))

    @Test
    fun segments_mapPov1ToPov2() {
        assertEquals(Counterpart(2, 61_000, CounterpartState.PLAYING, 0), cut.counterpart(10_000))
        assertEquals(Counterpart(2, 70_999, CounterpartState.PLAYING, 0), cut.counterpart(19_999))
        assertEquals(Counterpart(2, 74_000, CounterpartState.PLAYING, 1), cut.counterpart(20_000))
        assertEquals(51_000L, cut.offsetAt(19_999))
        assertEquals(54_000L, cut.offsetAt(20_000))
    }

    @Test
    fun segments_mapPov2BackAndFindTheGap() {
        assertEquals(Counterpart(1, 10_000, CounterpartState.PLAYING, 0), cut.counterpart(61_000))
        assertEquals(Counterpart(1, 20_000, CounterpartState.PLAYING, 1), cut.counterpart(74_000))
        // POV 2 from 71 s to 74 s shows what POV 1 lost
        val gap = cut.counterpart(72_000)
        assertEquals(CounterpartState.GAP, gap.state)
        assertEquals(1, gap.pov)
        assertTrue(gap.time in 19_000L..20_000L)
    }

    @Test
    fun segments_overlapTakesTheLatest() {
        // POV 2 lost 3 s instead: two POV 1 moments match the same POV 2 one
        val lost = DualViewTimeline(100_000, 0, 50_000, 100_000, listOf(Segment(0, 51_000), Segment(20_000, 48_000)))
        assertEquals(Counterpart(1, 22_000, CounterpartState.PLAYING, 1), lost.counterpart(70_000))
    }

    @Test
    fun bounds_leaveOutsideMomentsToNeither() {
        val bounded = DualViewTimeline(100_000, 5_000, 50_000, 95_000, listOf(Segment(5_000, 50_000)))
        assertEquals(0, bounded.povAt(4_999))
        assertEquals(1, bounded.povAt(5_000))
        assertEquals(2, bounded.povAt(94_999))
        assertEquals(0, bounded.povAt(95_000))
        assertEquals(CounterpartState.OUTSIDE, bounded.counterpart(1_000).state)
        assertEquals(CounterpartState.OUTSIDE, bounded.counterpart(97_000).state)
        // POV 2 ends at the end pin
        assertEquals(Counterpart(2, 95_000 - DualViewTimeline.EDGE_MARGIN, CounterpartState.ENDED, 0), bounded.counterpart(49_000))
        // POV 1 hasn't started for the start of POV 2
        assertEquals(Counterpart(1, 5_000, CounterpartState.NOT_STARTED, 0), bounded.counterpart(52_000))
    }

    @Test
    fun withBounds_keepsTheMatchingMoments() {
        val moved = cut.withBounds(25_000, 45_000, 100_000)
        assertEquals(listOf(Segment(25_000, 54_000)), moved.segments)
        assertEquals(Counterpart(2, 84_000, CounterpartState.PLAYING, 0), moved.counterpart(30_000))
        val earlier = cut.withBounds(2_000, 50_000, 90_000)
        assertEquals(listOf(Segment(2_000, 51_000), Segment(20_000, 54_000)), earlier.segments)
    }

    @Test
    fun cuts_canBeAddedMovedAndRemoved() {
        val added = cut.withCut(35_000, 55_000)
        assertEquals(3, added.segments.size)
        assertEquals(55_000L, added.offsetAt(40_000))
        // Too close to an existing cut: replaces it
        assertEquals(listOf(Segment(0, 51_000), Segment(20_500, 56_000)), cut.withCut(20_500, 56_000).segments)
        // At the very start: changes the first offset
        assertEquals(listOf(Segment(0, 52_000), Segment(20_000, 54_000)), cut.withCut(200, 52_000).segments)
        assertEquals(30_000L, added.withCutMoved(2, 30_000).segments[2].from)
        // Stays between its neighbours
        assertEquals(20_000L + DualViewTimeline.MIN_SEGMENT, added.withCutMoved(2, 10_000).segments[2].from)
        assertEquals(listOf(Segment(0, 51_000)), cut.withoutCut(1).segments)
        assertEquals(cut, cut.withoutCut(0))
    }

    @Test
    fun withOffsetAt_onlyChangesThatSegment() {
        val changed = cut.withOffsetAt(30_000, 54_100)
        assertEquals(listOf(Segment(0, 51_000), Segment(20_000, 54_100)), changed.segments)
    }

    @Test
    fun pov1TimeAt_followsTheSegments() {
        assertEquals(10_000L, cut.pov1TimeAt(10_000))
        assertEquals(25_000L, cut.pov1TimeAt(79_000))
    }
}
