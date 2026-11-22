package com.melodi.audio.remix

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * DJ stüdyosundaki davul ve efekt seslerini kodla üretir (hazır örnek dosya yok, telif yok).
 * Hepsi 44.1 kHz, mono, 16 bit PCM.
 */
object SampleSynth {
    const val RATE = 44_100

    enum class Sound(val label: String) {
        KICK("Kick"), SNARE("Snare"), CLAP("Clap"), HAT("Hi-hat"),
        BASS808("808"), TOM("Tom"), AIRHORN("Air horn"), LASER("Lazer"), SCRATCH("Scratch"),
    }

    fun render(sound: Sound): FloatArray = when (sound) {
        Sound.KICK -> kick()
        Sound.SNARE -> snare()
        Sound.CLAP -> clap()
        Sound.HAT -> hat()
        Sound.BASS808 -> bass808()
        Sound.TOM -> tom()
        Sound.AIRHORN -> airhorn()
        Sound.LASER -> laser()
        Sound.SCRATCH -> scratch()
    }

    private inline fun buffer(seconds: Double, f: (i: Int, t: Double) -> Double): FloatArray {
        val n = (seconds * RATE).toInt()
        return FloatArray(n) { i -> f(i, i.toDouble() / RATE).toFloat() }.normalized()
    }

    /** Frekansı zamanla değişen sinüs (faz birikimli, tık sesi yapmaz). */
    private fun sweep(seconds: Double, freq: (Double) -> Double, amp: (Double) -> Double): FloatArray {
        var phase = 0.0
        return buffer(seconds) { _, t ->
            phase += 2 * PI * freq(t) / RATE
            sin(phase) * amp(t)
        }
    }

    private fun kick() = sweep(0.45, { t -> 48 + 130 * exp(-t * 32) }) { t -> exp(-t * 7) * (if (t < 0.002) t / 0.002 else 1.0) }

    private fun snare(): FloatArray {
        val rnd = Random(1)
        var phase = 0.0
        return buffer(0.28) { _, t ->
            phase += 2 * PI * 185 / RATE
            (rnd.nextDouble(-1.0, 1.0) * 0.75 * exp(-t * 16)) + sin(phase) * 0.5 * exp(-t * 28)
        }
    }

    private fun hat(): FloatArray {
        val rnd = Random(2)
        var prev = 0.0
        return buffer(0.09) { _, t ->
            val n = rnd.nextDouble(-1.0, 1.0)
            val hp = n - prev // basit yüksek geçiren filtre: parlak, metalik
            prev = n
            hp * exp(-t * 55)
        }
    }

    private fun clap(): FloatArray {
        val rnd = Random(3)
        var prev = 0.0
        val bursts = doubleArrayOf(0.0, 0.011, 0.023)
        return buffer(0.32) { _, t ->
            val n = rnd.nextDouble(-1.0, 1.0)
            val bp = (n + prev) * 0.5
            prev = n
            val env = bursts.sumOf { b -> if (t >= b && t < b + 0.012) exp(-(t - b) * 180) else 0.0 } + if (t > 0.023) exp(-(t - 0.023) * 14) * 0.8 else 0.0
            bp * env
        }
    }

    private fun bass808() = sweep(1.1, { t -> 55 + 25 * exp(-t * 40) }) { t ->
        val s = exp(-t * 2.8)
        (s * 1.4).coerceIn(-1.0, 1.0) * (if (t < 0.003) t / 0.003 else 1.0)
    }.let { it.map { v -> softClip(v * 1.6f) }.toFloatArray().normalized() }

    private fun tom() = sweep(0.42, { t -> 95 + 70 * exp(-t * 18) }) { t -> exp(-t * 8) }

    /** Klasik DJ "air horn": testere dişi akor, titreşim ve üç kısa üfleme. */
    private fun airhorn(): FloatArray {
        val freqs = doubleArrayOf(466.16, 554.37, 698.46)
        val phases = DoubleArray(freqs.size)
        val blasts = listOf(0.0 to 0.18, 0.22 to 0.18, 0.44 to 0.55)
        return buffer(1.05) { _, t ->
            val vib = 1 + 0.012 * sin(2 * PI * 6.5 * t)
            var v = 0.0
            for (k in freqs.indices) {
                phases[k] += freqs[k] * vib / RATE
                v += 2 * (phases[k] - Math.floor(phases[k] + 0.5)) // testere dişi
            }
            val env = blasts.sumOf { (s, d) -> if (t >= s && t < s + d) minOf(1.0, (t - s) / 0.01, (s + d - t) / 0.03) else 0.0 }
            softClip((v / freqs.size * env * 1.3).toFloat()).toDouble()
        }
    }

    private fun laser() = sweep(0.38, { t -> 2400 * exp(-t * 9) + 120 }) { t -> exp(-t * 5) }

    /** Plak scratch'i: ileri-geri iki vuruşta perdesi kayan, gürültülü ses. */
    private fun scratch(): FloatArray {
        val rnd = Random(4)
        var phase = 0.0
        return buffer(0.34) { _, t ->
            val stroke = sin(PI * (t / 0.17)) // iki yarım vuruş
            val f = 260 + 900 * abs(stroke)
            phase += 2 * PI * f / RATE
            val saw = 2 * (phase / (2 * PI) - Math.floor(phase / (2 * PI) + 0.5))
            (saw * 0.7 + rnd.nextDouble(-0.3, 0.3)) * abs(stroke)
        }
    }

    private fun softClip(x: Float): Float = if (abs(x) < 1f) x - x * x * x / 3f else sign(x) * 2f / 3f

    private fun FloatArray.normalized(peak: Float = 0.9f): FloatArray {
        val max = maxOfOrNull { abs(it) } ?: 0f
        if (max <= 0f) return this
        val g = peak / max
        for (i in indices) this[i] *= g
        return this
    }

    /** 16 bit PCM WAV dosyası (SoundPool doğrudan yükler). */
    fun toWav(samples: FloatArray): ByteArray {
        val dataSize = samples.size * 2
        val bytes = ByteArrayOutputStream(44 + dataSize)
        DataOutputStream(bytes).use { out ->
            fun intLE(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff); out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff) }
            fun shortLE(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
            out.writeBytes("RIFF"); intLE(36 + dataSize); out.writeBytes("WAVE")
            out.writeBytes("fmt "); intLE(16); shortLE(1); shortLE(1); intLE(RATE); intLE(RATE * 2); shortLE(2); shortLE(16)
            out.writeBytes("data"); intLE(dataSize)
            for (s in samples) shortLE((s.coerceIn(-1f, 1f) * 32767).toInt())
        }
        return bytes.toByteArray()
    }
}
