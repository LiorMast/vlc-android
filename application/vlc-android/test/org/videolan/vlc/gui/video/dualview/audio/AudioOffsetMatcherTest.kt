package org.videolan.vlc.gui.video.dualview.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

@RunWith(JUnit4::class)
class AudioOffsetMatcherTest {

    private val rate = 8000
    private val split = 60_000L
    private val length = 120_000L

    /**
     * Sound of an event: noise bursts at random moments
     */
    private class Event(seed: Int, durationMs: Long, periodMs: Long = 0L) {
        private val starts: List<Long>
        private val noise = Random(seed)

        init {
            val random = Random(seed + 1)
            starts = if (periodMs > 0) (0 until durationMs step periodMs).toList()
            else generateSequence(0L) { it + 100 + random.nextLong(600) }.takeWhile { it < durationMs }.toList()
        }

        fun at(ms: Double): Float {
            val index = starts.binarySearch(ms.toLong()).let { if (it >= 0) it else -it - 2 }
            if (index < 0) return 0F
            val age = ms - starts[index]
            return if (age > 40) 0F else (exp(-age / 10.0) * (noise.nextFloat() * 2 - 1)).toFloat()
        }
    }

    /**
     * POV 1 hears [event] directly, POV 2 [delayMs] later and quieter; each with its own noise
     */
    private fun stitched(event: Event, pov2Event: Event, delayMs: Double): (Double) -> Float {
        val hiss = Random(7)
        return { ms ->
            val sound = if (ms < split) event.at(ms) else 0.5F * pov2Event.at(ms - split - delayMs)
            sound + 0.01F * (hiss.nextFloat() * 2 - 1)
        }
    }

    private fun envelope(audio: (Double) -> Float, range: LongRange): AudioEnvelope {
        val builder = EnvelopeBuilder(range.first, range.last)
        val count = ((range.last - range.first) * rate / 1000).toInt()
        val samples = FloatArray(count) { audio(range.first + it * 1000.0 / rate) }
        builder.add(range.first * 1000, rate, samples, count)
        return builder.build()
    }

    private fun match(audio: (Double) -> Float): AudioOffsetMatcher.Match? {
        val window = 20_000L
        val range = 30_000L
        val windows = listOf(10_000L, 25_000L, 40_000L).map { start ->
            val pov2Start = maxOf(start + split - range, split)
            val pov2End = minOf(start + split + window + range, length)
            AudioOffsetMatcher.Window(envelope(audio, start..start + window), envelope(audio, pov2Start..pov2End))
        }
        return AudioOffsetMatcher.match(windows, split, range)
    }

    @Test
    fun findsTheDelayOfTheSecondRecording() {
        val event = Event(1, length)
        val result = match(stitched(event, event, 1_234.0))
        assertNotNull(result)
        assertTrue("score ${result!!.score}, runner-up ${result.runnerUp}", result.accepted)
        assertEquals((split + 1_234).toDouble(), result.offsetMs.toDouble(), 10.0)
    }

    @Test
    fun findsAnEarlierSecondRecording() {
        val event = Event(2, length)
        val result = match(stitched(event, event, -8_765.0))!!
        assertTrue(result.accepted)
        assertTrue("offset ${result.offsetMs}", abs(result.offsetMs - (split - 8_765)) <= 10)
    }

    /**
     * Two cameras 25 m apart: each microphone hears every sound after its own travel time and at
     * its own level, POV 2 over strong wind, both over a loud bass beat from a PA
     */
    @Test
    fun findsTheOffsetWhenMicrophonesHearDifferentMixes() {
        val impacts = Event(6, length) // near camera 2
        val voice = Event(8, length) // next to camera 1
        val murmur = Random(9)
        val gusts = Random(10)
        var wind = 0F
        // 55 Hz kick drum every 500 ms
        fun beat(ms: Double): Float = if (ms < 0) 0F else (sin(2 * PI * 55 * ms / 1000) * exp(-ms.mod(500.0) / 60)).toFloat()
        val lead = 1_234.0
        val audio = { ms: Double ->
            val noise = 0.01F * (murmur.nextFloat() * 2 - 1)
            if (ms < split) {
                0.3F * impacts.at(ms - 55) + voice.at(ms - 3) + 2F * beat(ms - 122) + noise
            } else {
                // POV 2 started [lead] earlier
                val event = ms - split - lead
                wind = 0.999F * wind + 0.05F * (gusts.nextFloat() * 2 - 1)
                0.7F * impacts.at(event - 27) + 0.04F * voice.at(event - 70) + 2F * beat(event - 123) + 3F * wind + noise
            }
        }
        val result = match(audio)!!
        assertTrue("score ${result.score}, runner-up ${result.runnerUp}", result.accepted)
        // The sounds' travel times differ by up to 67 ms between the microphones
        assertEquals((split + 1_234).toDouble(), result.offsetMs.toDouble(), 70.0)
    }

    @Test
    fun rejectsUnrelatedAudio() {
        val result = match(stitched(Event(3, length), Event(4, length), 0.0))
        assertFalse("score ${result?.score}, runner-up ${result?.runnerUp}", result?.accepted == true)
    }

    @Test
    fun rejectsAmbiguousRhythm() {
        val metronome = Event(5, length, periodMs = 500)
        val result = match(stitched(metronome, metronome, 1_234.0))
        assertFalse("score ${result?.score}, runner-up ${result?.runnerUp}", result?.accepted == true)
    }
}
