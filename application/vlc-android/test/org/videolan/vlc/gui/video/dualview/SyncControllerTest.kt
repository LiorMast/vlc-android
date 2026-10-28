package org.videolan.vlc.gui.video.dualview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.videolan.vlc.gui.video.dualview.SyncController.Action

@RunWith(JUnit4::class)
class SyncControllerTest {

    private val timeline = DualViewTimeline(100_000, 50_000, 50_000)
    private lateinit var controller: SyncController

    @Before
    fun setUp() {
        controller = SyncController()
        controller.timeline = timeline
        controller.audioPov = 1
        controller.reset(0)
    }

    private fun input(now: Long, main: Long, slave: Long, mainPlaying: Boolean = true, slavePlaying: Boolean = true, slaveRate: Float = 1F, seekTarget: Long = -1L, jumped: Boolean = false) =
            SyncController.Input(now = now, mainTime = main, mainPlaying = mainPlaying, mainRate = 1F, mainSeekTarget = seekTarget, mainJumped = jumped,
                    slaveTime = slave, slavePlaying = slavePlaying, slaveRate = slaveRate)

    private fun List<Action>.withoutUiUpdates() = filter { it !is Action.CounterpartChanged }

    @Test
    fun waitsForTheSecondPlayerToSettleAtStart() {
        val actions = controller.update(input(100, 10_000, 61_000))
        assertEquals(listOf(Action.CounterpartChanged(Counterpart(2, 60_000, CounterpartState.PLAYING))), actions)
    }

    @Test
    fun smallDriftIsCorrectedWithTheRate() {
        // The window runs 120 ms ahead: slow it down
        val actions = controller.update(input(2_000, 10_000, 60_120)).withoutUiUpdates()
        val rate = (actions.single() as Action.SetRate).rate
        assertTrue(rate < 1F && rate >= 1F - SyncController.MAX_ADJUST)
    }

    @Test
    fun audibleWindowIsCorrectedGently() {
        controller.audioPov = 2
        val actions = controller.update(input(2_000, 10_000, 60_300)).withoutUiUpdates()
        assertEquals(1F - SyncController.MAX_ADJUST_AUDIBLE, (actions.single() as Action.SetRate).rate, 0.0001F)
    }

    @Test
    fun driftOfHalfASecondSeeks() {
        // Correcting it with the playback rate would take too long
        val actions = controller.update(input(2_000, 10_000, 60_500)).withoutUiUpdates()
        assertTrue(actions.first() is Action.Seek)
    }

    @Test
    fun inStepWindowGetsTheMainRateBack() {
        val actions = controller.update(input(2_000, 10_000, 60_005, slaveRate = 0.9F)).withoutUiUpdates()
        assertEquals(listOf(Action.SetRate(1F)), actions)
    }

    @Test
    fun largeDriftSeeksAhead() {
        val actions = controller.update(input(2_000, 10_000, 75_000)).withoutUiUpdates()
        val seek = actions.first() as Action.Seek
        // Aimed ahead by the time the seek takes
        assertEquals(60_000 + SyncController.OPEN_LEAD, seek.time)
        // Then it waits for the seek to complete
        assertTrue(controller.update(input(2_100, 10_100, 75_100)).withoutUiUpdates().isEmpty())
    }

    @Test
    fun mainSeekFromTheUiMovesTheWindowExactly() {
        val actions = controller.update(input(2_000, 10_000, 60_000, seekTarget = 30_000)).withoutUiUpdates()
        assertEquals(listOf(Action.Seek(80_000)), actions)
    }

    @Test
    fun pausedMainPausesTheWindowWithoutSeeking() {
        val actions = controller.update(input(2_000, 10_000, 60_000, mainPlaying = false)).withoutUiUpdates()
        assertEquals(listOf(Action.Pause), actions)
        // Resuming needs no seek: the window was held on the matching frame
        val resumed = controller.update(input(3_000, 10_000, 60_000, slavePlaying = false)).withoutUiUpdates()
        assertEquals(listOf(Action.Play), resumed)
    }

    @Test
    fun seekWhilePausedShowsTheMatchingFrame() {
        controller.update(input(2_000, 10_000, 60_000, mainPlaying = false))
        val actions = controller.update(input(2_100, 20_000, 60_000, mainPlaying = false, slavePlaying = false, seekTarget = 20_000)).withoutUiUpdates()
        assertEquals(listOf(Action.Seek(70_000)), actions)
    }

    @Test
    fun pausedWindowAimsAtTheMiddleOfFrames() {
        controller.frameDuration = 1000.0 / 30
        controller.update(input(2_000, 10_000, 60_000, mainPlaying = false))
        // At 30 fps the frame showing at 70 000 ms runs to 70 033.3
        val actions = controller.update(input(2_100, 20_000, 60_000, mainPlaying = false, slavePlaying = false, seekTarget = 20_000)).withoutUiUpdates()
        assertEquals(listOf(Action.Seek(70_017)), actions)
    }

    @Test
    fun midFrameMovesByWholeFrames() {
        val frame = 1000.0 / 30
        // Anywhere in a frame aims at its middle
        assertEquals(94_117L, SyncController.midFrame(94_100, frame))
        assertEquals(94_117L, SyncController.midFrame(94_133, frame))
        // One frame duration further is the next frame's middle
        assertEquals(94_150L, SyncController.midFrame(94_117 + 33, frame))
        assertEquals(94_183L, SyncController.midFrame(94_117 + 67, frame))
        assertEquals(1_234L, SyncController.midFrame(1_234, 0.0))
    }

    @Test
    fun offsetChangeWhilePausedRealigns() {
        controller.update(input(2_000, 10_000, 60_000, mainPlaying = false))
        controller.timeline = timeline.withOffset(50_050)
        controller.onTimelineChanged()
        val actions = controller.update(input(2_100, 10_000, 60_000, mainPlaying = false, slavePlaying = false)).withoutUiUpdates()
        assertEquals(listOf(Action.Seek(60_050)), actions)
    }

    @Test
    fun windowParksOnceWhenItsPovHasNoFootage() {
        controller.timeline = DualViewTimeline(100_000, 50_000, 45_000)
        val actions = controller.update(input(2_000, 1_000, 51_000))
        assertTrue(actions.contains(Action.CounterpartChanged(Counterpart(2, 50_000, CounterpartState.NOT_STARTED))))
        assertTrue(actions.contains(Action.Pause))
        assertTrue(actions.contains(Action.Seek(50_000)))
        // Still no footage: nothing more to do
        assertTrue(controller.update(input(2_100, 1_100, 50_000, slavePlaying = false)).isEmpty())
        // Footage starts: play it from the matching moment
        val started = controller.update(input(8_000, 5_100, 50_000, slavePlaying = false))
        assertTrue(started.any { it is Action.Seek && it.time >= 50_100 })
        assertTrue(started.contains(Action.Play))
    }

    @Test
    fun pausesTheMainPlayerAtTheEndOfPov1() {
        controller.update(input(2_000, 49_700, 99_000 - 300))
        val actions = controller.update(input(2_100, 49_860, 99_160))
        assertTrue(actions.contains(Action.PauseMain(1)))
    }

    @Test
    fun noPauseWhenJumpingPastTheEndOfPov1() {
        controller.update(input(2_000, 10_000, 60_000))
        val actions = controller.update(input(2_100, 49_900, 60_100, seekTarget = 49_900))
        assertFalse(actions.any { it is Action.PauseMain })
    }
}
