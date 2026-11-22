package com.melodi.audio.remix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Mikser hesapları (saf fonksiyonlar, test edilebilir). */
object MixMath {
    /**
     * Eşit güçlü crossfader eğrisi: 0 = yalnız A, 1 = yalnız B.
     * Ortada iki parça da ~%71 seste çalar; doğrusal eğrideki "ortada ses düşmesi" olmaz.
     */
    fun crossfadeVolumes(x: Float): Pair<Float, Float> {
        val t = x.coerceIn(0f, 1f) * PI / 2
        return cos(t).toFloat() to sin(t).toFloat()
    }

}

/** Ritme dokunarak BPM bulma: son dokunuşların ortalama aralığı; 2 sn ara verilirse sıfırlanır. */
class TapTempo(private val clock: () -> Long = System::nanoTime) {
    private val taps = ArrayDeque<Long>()

    /** Yeterli dokunuş varsa yeni BPM, yoksa null. */
    fun tap(): Int? {
        val now = clock()
        if (taps.isNotEmpty() && now - taps.last() > 2_000_000_000L) taps.clear()
        taps.addLast(now)
        while (taps.size > 6) taps.removeFirst()
        if (taps.size < 3) return null
        val avg = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
        return (60_000_000_000.0 / avg).roundToInt().coerceIn(60, 200)
    }
}
