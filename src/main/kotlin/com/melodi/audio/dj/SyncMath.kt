package com.melodi.audio.dj

import kotlin.math.abs
import kotlin.math.floor

/** Ortak tempo saati hesapları (saf; birim testli). */
object SyncMath {
    /** Ana tempoya en yakın ızgara: bpm, 2×bpm ya da bpm/2 (ör. 64 BPM'lik parça 128'lik sete 128 olarak oturur). */
    fun gridBpm(bpm: Float, target: Float): Float = listOf(bpm, bpm * 2, bpm / 2).minBy { abs(it - target) }

    /** Deck'in etkin temposunu [masterEffBpm]'e eşitleyen hız çarpanı. */
    fun syncSpeed(masterEffBpm: Float, bpm: Float): Float = masterEffBpm / gridBpm(bpm, masterEffBpm)

    /** Vuruş fazı 0..1 (ilk vuruştan bu yana geçen vuruş sayısının kesir kısmı). */
    fun phase(positionMs: Double, firstBeatMs: Double, bpm: Float): Double {
        val x = (positionMs - firstBeatMs) / (60_000.0 / bpm)
        return x - floor(x)
    }

    /** Faz farkı vuruş cinsinden −0,5..0,5; pozitif = deck ana deck'in gerisinde. */
    fun phaseError(masterPhase: Double, phase: Double): Float {
        var e = (masterPhase - phase).toFloat()
        if (e > 0.5f) e -= 1f
        if (e < -0.5f) e += 1f
        return e
    }

    /**
     * Faz farkını kapatan geçici tempo düzeltmesi: kademeli (±%0,5 / ±%1,5 / ±%3) ve histerezisli
     * (6 ms'de devreye girer, 3 ms'de çıkar). Her tempo değişikliği ses zincirini boşalttığı için
     * sürekli değişen bir değer yerine az sayıda kademe kullanılır.
     */
    fun correction(errorMs: Float, previous: Float = 1f): Float {
        val a = abs(errorMs)
        if (a <= if (previous != 1f) 3f else 6f) return 1f
        val step = when {
            a <= 30f -> 0.005f
            a <= 120f -> 0.015f
            else -> 0.03f
        }
        return 1f + if (errorMs > 0) step else -step
    }
}
