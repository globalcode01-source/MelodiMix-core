package com.melodi.audio.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class AnalyzerTest {
    private val sr = 11025

    private fun run(samples: FloatArray): AnalysisResult {
        val a = AnalysisAccumulator(sr, samples.size * 1000L / sr)
        var i = 0
        while (i < samples.size) {
            val n = minOf(4096, samples.size - i)
            a.push(samples.copyOfRange(i, i + n), n)
            i += n
        }
        return a.finish()
    }

    /** [bpm]'de vuruşlar (bas + tık), ilk vuruş [offsetMs]'de, arka planda hafif gürültü. */
    private fun beatTrack(bpm: Double, seconds: Int, offsetMs: Double, seed: Int = 1): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray(sr * seconds) { rnd.nextFloat() * 0.02f - 0.01f }
        val period = 60.0 / bpm * sr
        var t = offsetMs / 1000 * sr
        while (t < out.size) {
            val start = t.toInt()
            for (k in 0 until (0.12 * sr).toInt()) {
                val i = start + k
                if (i >= out.size) break
                val env = exp(-k / (0.03 * sr)).toFloat()
                out[i] += env * (0.8f * sin(2 * PI * 60 * k / sr).toFloat() + 0.3f * (rnd.nextFloat() * 2 - 1))
            }
            t += period
        }
        return out
    }

    @Test
    fun detectsTempoAndBeatPhase() {
        val r = run(beatTrack(128.0, 40, 250.0))
        assertNotNull(r.bpm)
        assertEquals(128f, r.bpm!!, 0.1f)
        val beat = 60_000.0 / 128
        val phaseErr = ((r.firstBeatMs!! - 250.0) % beat + beat) % beat
        assertTrue("faz hatası $phaseErr ms", phaseErr < 40 || phaseErr > beat - 40)
    }

    @Test
    fun detectsOtherTempi() {
        assertEquals(96f, run(beatTrack(96.0, 40, 0.0, 2)).bpm!!, 0.15f)
        assertEquals(140f, run(beatTrack(140.0, 40, 100.0, 3)).bpm!!, 0.15f)
    }

    /** House kalıbı: vuruşta kick, ara vuruşta yüksek sesli hi-hat. Izgara kick'e oturmalı. */
    @Test
    fun beatGridLocksToKickNotOffbeatHats() {
        val bpm = 124.0
        val rnd = Random(9)
        val out = FloatArray(sr * 40)
        val period = 60.0 / bpm * sr
        var t = 0.0
        while (t < out.size) {
            val k0 = t.toInt()
            for (k in 0 until (0.15 * sr).toInt()) {
                if (k0 + k >= out.size) break
                out[k0 + k] += (0.7 * exp(-k / (0.04 * sr)) * sin(2 * PI * (50 + 80 * exp(-k / (0.01 * sr))) * k / sr)).toFloat()
            }
            val h0 = (t + period / 2).toInt()
            for (k in 0 until (0.05 * sr).toInt()) {
                if (h0 + k >= out.size) break
                out[h0 + k] += (0.5 * exp(-k / (0.01 * sr)) * (rnd.nextFloat() * 2 - 1)).toFloat()
            }
            t += period
        }
        val r = run(out)
        assertEquals(124f, r.bpm!!, 0.15f)
        val beat = 60_000.0 / bpm
        val phaseErr = (r.firstBeatMs!! % beat + beat) % beat
        assertTrue("faz hatası $phaseErr ms (yarım vuruş = ${beat / 2})", phaseErr < 50 || phaseErr > beat - 50)
    }

    /** Bassız parça (yalnızca yüksek frekanslı tıklar): faz yine doğru bulunmalı (bas ağırlığı gürültüyü büyütmemeli). */
    @Test
    fun beatPhaseWithoutBass() {
        val bpm = 120.0
        val rnd = Random(4)
        val out = FloatArray(sr * 30) { rnd.nextFloat() * 0.004f - 0.002f }
        val period = 60.0 / bpm * sr
        var t = 0.3 * sr
        while (t < out.size) {
            val k0 = t.toInt()
            for (k in 0 until (0.03 * sr).toInt()) {
                if (k0 + k >= out.size) break
                out[k0 + k] += (0.6 * exp(-k / (0.006 * sr)) * sin(2 * PI * 2500 * k / sr)).toFloat()
            }
            t += period
        }
        val r = run(out)
        assertEquals(120f, r.bpm!!, 0.15f)
        val beat = 60_000.0 / bpm
        val phaseErr = ((r.firstBeatMs!! - 300.0) % beat + beat) % beat
        assertTrue("faz hatası $phaseErr ms", phaseErr < 30 || phaseErr > beat - 30)
    }

    @Test
    fun noBeatMeansUnknownTempo() {
        val rnd = Random(5)
        val noise = FloatArray(sr * 30) { rnd.nextFloat() * 0.2f - 0.1f }
        assertNull(run(noise).bpm)
    }

    private fun chord(freqs: List<Double>, seconds: Int): FloatArray = FloatArray(sr * seconds) { i ->
        var v = 0.0
        for (f in freqs) for (h in 1..3) v += sin(2 * PI * f * h * i / sr) / (h * h)
        (v * 0.15).toFloat()
    }

    @Test
    fun detectsMinorAndMajorKeys() {
        // A minör: A3, C4, E4 (+ A2 bas)
        assertEquals("Am", run(chord(listOf(110.0, 220.0, 261.63, 329.63), 20)).key?.name)
        // C majör: C3, E4, G4, C4
        assertEquals("C", run(chord(listOf(130.81, 261.63, 329.63, 392.0), 20)).key?.name)
    }

    @Test
    fun energyOrdersLoudBusyAboveQuietSparse() {
        val loud = run(beatTrack(128.0, 30, 0.0)).energy!!
        val quiet = run(FloatArray(sr * 30) { (sin(2 * PI * 220 * it / sr) * 0.02).toFloat() }).energy!!
        assertTrue("$loud > $quiet", loud > quiet + 20)
    }

    @Test
    fun waveformHasRequestedResolution() {
        val r = run(beatTrack(120.0, 20, 0.0))
        assertEquals(800, r.wavePeaks.size)
        assertTrue(r.wavePeaks.any { (it.toInt() and 0xff) > 200 })
    }

    @Test
    fun sectionsFromEnergyCurve() {
        // 10 sn sessiz intro, 40 sn yüksek, 15 sn sessiz outro
        val quiet = FloatArray(sr) { (sin(2 * PI * 220 * it / sr) * 0.02).toFloat() }
        val win = FloatArray(65) { i -> if (i in 10 until 50) 0.5f else 0.05f }
        val (intro, outro, _) = AnalysisAccumulator.estimateSections(win, null)
        assertEquals(10_000L, intro)
        assertEquals(50_000L, outro)
        assertTrue(quiet.isNotEmpty())
    }
}
