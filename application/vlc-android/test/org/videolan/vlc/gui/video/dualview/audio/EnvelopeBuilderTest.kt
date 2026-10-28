package org.videolan.vlc.gui.video.dualview.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class EnvelopeBuilderTest {

    @Test
    fun placesSamplesInTheirSteps() {
        // 44.1 kHz: 441 samples per 10 ms step, starting 25 ms after the envelope
        val rate = 44_100
        val builder = EnvelopeBuilder(0, 60, highPassHz = 0F)
        // Samples 0 to 220 play before 30 ms (sample 220 at 29.989 ms)
        val samples = FloatArray(441 * 2) { if (it <= 220) 1F else 0.5F }
        builder.add(25_000, rate, samples, samples.size)
        val energy = builder.build().energy
        assertEquals(6, energy.size)
        assertTrue(energy[0].isNaN() && energy[1].isNaN() && energy[5].isNaN())
        // Step 2 (20-30 ms) gets the samples played from 25 ms, all at 1
        assertEquals(1F, energy[2], 0.0001F)
        assertEquals(0.25F, energy[3], 0.0001F)
        assertEquals(0.25F, energy[4], 0.0001F)
    }

    @Test
    fun interleavedSamplesMatchTheirMonoMix() {
        val rate = 48_000
        val frames = 4_800
        val stereo = ShortArray(frames * 2) { (if (it % 2 == 0) (it * 37) % 20_000 else -(it * 11) % 15_000).toShort() }
        val mono = FloatArray(frames) { (stereo[2 * it] + stereo[2 * it + 1]) / 2F / 32768F }
        val fromShorts = EnvelopeBuilder(0, 100).apply { add16(0, rate, stereo, frames, 2) }.build().energy
        val fromFloats = EnvelopeBuilder(0, 100).apply { add(0, rate, mono, frames) }.build().energy
        assertArrayEquals(fromFloats, fromShorts, 1e-6F)
        val interleavedFloats = FloatArray(frames * 2) { stereo[it] / 32768F }
        val fromInterleaved = EnvelopeBuilder(0, 100).apply { addFloat(0, rate, interleavedFloats, frames, 2) }.build().energy
        assertArrayEquals(fromFloats, fromInterleaved, 1e-6F)
    }
}
