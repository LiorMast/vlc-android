package org.videolan.vlc.gui.video.dualview.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.videolan.vlc.gui.video.dualview.audio.CutFinder.Run
import org.videolan.vlc.gui.video.dualview.audio.CutFinder.WindowMatch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

@RunWith(JUnit4::class)
class CutFinderTest {

    private val rate = 8000
    private val split = 60_000L
    private val length = 130_000L
    // POV 1 lost the 3 s of the event that followed its 35th second
    private val cutAt = 35_000L
    private val lost = 3_000L
    // POV 2 started recording 1 s before POV 1
    private val offsetBefore = split + 1_000
    private val offsetAfter = offsetBefore + lost

    /**
     * Sound of an event: noise bursts at random moments
     */
    private class Event(seed: Int, durationMs: Long) {
        private val starts: List<Long>
        private val noise = Random(seed)

        init {
            val random = Random(seed + 1)
            starts = generateSequence(0L) { it + 100 + random.nextLong(600) }.takeWhile { it < durationMs }.toList()
        }

        fun at(ms: Double): Float {
            val index = starts.binarySearch(ms.toLong()).let { if (it >= 0) it else -it - 2 }
            if (index < 0) return 0F
            val age = ms - starts[index]
            return if (age > 40) 0F else (exp(-age / 10.0) * (noise.nextFloat() * 2 - 1)).toFloat()
        }
    }

    private val event = Event(11, length)
    private val hiss = Random(12)

    /**
     * The file: POV 1 with its cut, then POV 2 hearing the event a bit quieter
     */
    private fun audio(ms: Double): Float {
        val sound = when {
            ms < cutAt -> event.at(ms)
            ms < split -> event.at(ms + lost)
            else -> 0.5F * event.at(ms - offsetBefore)
        }
        return sound + 0.01F * (hiss.nextFloat() * 2 - 1)
    }

    private fun envelope(range: LongRange): AudioEnvelope {
        val builder = EnvelopeBuilder(range.first, range.last)
        val count = ((range.last - range.first) * rate / 1000).toInt()
        val samples = FloatArray(count) { audio(range.first + it * 1000.0 / rate) }
        builder.add(range.first * 1000, rate, samples, count)
        return builder.build()
    }

    private val pov1 by lazy { envelope(0L until split) }
    private val pov2 by lazy { envelope(split until length) }

    @Test
    fun matchWindow_findsEachSidesOffset() {
        val before = CutFinder.matchWindow(pov1, pov2, 10_000, 12_000, split, 30_000, split, length)!!
        assertTrue(before.accepted)
        assertEquals(offsetBefore.toDouble(), before.offsetMs.toDouble(), 10.0)
        val after = CutFinder.matchWindow(pov1, pov2, 40_000, 12_000, split, 30_000, split, length)!!
        assertTrue(after.accepted)
        assertEquals(offsetAfter.toDouble(), after.offsetMs.toDouble(), 10.0)
    }

    @Test
    fun matchWindow_wholeRangeFindsAFarOffset() {
        // Searching from a guess 40 s off: only a search across all of POV 2 finds it
        val base = offsetBefore + 40_000
        val range = CutFinder.wholeRange(10_000, 12_000, base, split, length)
        val match = CutFinder.matchWindow(pov1, pov2, 10_000, 12_000, base, range, split, length)!!
        assertTrue("score ${match.score}, runner-up ${match.runnerUp}", match.accepted)
        assertEquals(offsetBefore.toDouble(), match.offsetMs.toDouble(), 10.0)
    }

    @Test
    fun findCut_findsWhereTheOffsetChanges() {
        val cut = CutFinder.findCut(pov1.slice(20_000, 52_000), pov2, offsetBefore, offsetAfter)
        assertTrue("cut at $cut", abs(cut - cutAt) <= 500)
    }

    @Test
    fun scan_findsTheSegments() {
        val segments = CutFinder.scan(pov1, pov2, 0, split, length, split)
        assertNotNull(segments)
        assertEquals("$segments", 2, segments!!.size)
        assertEquals(0L, segments[0].from)
        assertEquals(offsetBefore.toDouble(), segments[0].offset.toDouble(), 10.0)
        assertTrue("cut at ${segments[1].from}", abs(segments[1].from - cutAt) <= 500)
        assertEquals(offsetAfter.toDouble(), segments[1].offset.toDouble(), 10.0)
    }

    @Test
    fun runs_groupAgreeingWindowsAndDropLoneOutliers() {
        val matches = listOf(
                WindowMatch(0, 1_000), WindowMatch(20_000, 1_020), WindowMatch(40_000, null),
                WindowMatch(60_000, 7_000), // false match
                WindowMatch(80_000, 1_010), WindowMatch(100_000, 4_000), WindowMatch(120_000, 4_030))
        assertEquals(listOf(Run(0, 4, 1_010), Run(5, 6, 4_030)), CutFinder.runs(matches))
    }
}
