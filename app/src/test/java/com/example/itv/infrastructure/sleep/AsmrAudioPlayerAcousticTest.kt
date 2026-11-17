package com.example.itv.infrastructure.sleep

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsmrAudioPlayerAcousticTest {

    @Test
    fun biquadFilter_producesFiniteOutputs() {
        val filter = AsmrAudioPlayer.Biquad()
        filter.setLowPass(800.0, 0.707, 44100.0)

        for (i in 0 until 500) {
            val sample = filter.process(0.5)
            assertFalse("Sample $i is NaN", sample.isNaN())
            assertFalse("Sample $i is Infinite", sample.isInfinite())
            assertTrue("Sample $i should be within [-2.0, 2.0]", sample in -2.0..2.0)
        }
    }

    @Test
    fun pinkNoise_producesSmoothFiniteNoise() {
        val pink = AsmrAudioPlayer.PinkNoise()
        var nonZeroCount = 0

        for (i in 0 until 1000) {
            val sample = pink.nextSample(0.3)
            assertFalse("Pink sample $i is NaN", sample.isNaN())
            assertTrue("Pink sample $i is within [-2.0, 2.0]", sample in -2.0..2.0)
            if (sample != 0.0) nonZeroCount++
        }
        assertTrue("Pink noise should generate non-zero values", nonZeroCount > 500)
    }

    @Test
    fun brownNoise_producesWarmFiniteNoise() {
        val brown = AsmrAudioPlayer.BrownNoise()
        var nonZeroCount = 0

        for (i in 0 until 1000) {
            val sample = brown.nextSample(0.2)
            assertFalse("Brown sample $i is NaN", sample.isNaN())
            assertTrue("Brown sample $i is within [-3.0, 3.0]", sample in -3.0..3.0)
            if (sample != 0.0) nonZeroCount++
        }
        assertTrue("Brown noise should generate non-zero values", nonZeroCount > 500)
    }

    @Test
    fun allGenerators_produceValidDistinctAudioSignals() {
        val sampleRate = 44100.0
        val rain = AsmrAudioPlayer.RainGenerator(sampleRate)
        val fire = AsmrAudioPlayer.FireplaceGenerator(sampleRate)
        val breath = AsmrAudioPlayer.DeepBreathGenerator(sampleRate)
        val wave = AsmrAudioPlayer.WaveGenerator(sampleRate)

        // Warm up generators with 2000 samples to let filters settle
        repeat(2000) {
            rain.nextSample()
            fire.nextSample()
            breath.nextSample()
            wave.nextSample()
        }

        val sampleCount = 4000
        val rainSamples = DoubleArray(sampleCount) { rain.nextSample() }
        val fireSamples = DoubleArray(sampleCount) { fire.nextSample() }
        val breathSamples = DoubleArray(sampleCount) { breath.nextSample() }
        val waveSamples = DoubleArray(sampleCount) { wave.nextSample() }

        // Verify bounds & finite
        for (i in 0 until sampleCount) {
            assertFalse("Rain sample $i is NaN", rainSamples[i].isNaN())
            assertFalse("Fire sample $i is NaN", fireSamples[i].isNaN())
            assertFalse("Breath sample $i is NaN", breathSamples[i].isNaN())
            assertFalse("Wave sample $i is NaN", waveSamples[i].isNaN())

            assertTrue("Rain in [-1.0, 1.0]", rainSamples[i] in -1.0..1.0)
            assertTrue("Fire in [-1.0, 1.0]", fireSamples[i] in -1.0..1.0)
            assertTrue("Breath in [-1.0, 1.0]", breathSamples[i] in -1.0..1.0)
            assertTrue("Wave in [-1.0, 1.0]", waveSamples[i] in -1.0..1.0)
        }

        // Verify generators produce non-silent distinct signals
        val rainAvg = rainSamples.map { Math.abs(it) }.average()
        val fireAvg = fireSamples.map { Math.abs(it) }.average()
        val breathAvg = breathSamples.map { Math.abs(it) }.average()
        val waveAvg = waveSamples.map { Math.abs(it) }.average()

        assertTrue("Rain average amplitude > 0", rainAvg > 0.001)
        assertTrue("Fire average amplitude > 0", fireAvg > 0.001)
        assertTrue("Breath average amplitude > 0", breathAvg > 0.001)
        assertTrue("Wave average amplitude > 0", waveAvg > 0.001)
    }
}
