package com.melodi.audio.remix

import com.melodi.audio.remix.MixMath
import com.melodi.audio.remix.TapTempo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MixMathTest {
    @Test
    fun `crossfader uçlarda tek deck, ortada eşit güç`() {
        val (a0, b0) = MixMath.crossfadeVolumes(0f)
        assertEquals(1f, a0, 1e-5f); assertEquals(0f, b0, 1e-5f)
        val (a1, b1) = MixMath.crossfadeVolumes(1f)
        assertEquals(0f, a1, 1e-5f); assertEquals(1f, b1, 1e-5f)
        val (am, bm) = MixMath.crossfadeVolumes(0.5f)
        assertEquals(am, bm, 1e-5f)
        // Toplam güç her yerde sabit: a² + b² = 1
        listOf(0.1f, 0.3f, 0.5f, 0.8f).forEach { x ->
            val (a, b) = MixMath.crossfadeVolumes(x)
            assertEquals(1f, a * a + b * b, 1e-4f)
        }
    }

    @Test
    fun `tap tempo ortalama aralıktan bpm bulur`() {
        var now = 0L
        val tap = TapTempo { now }
        assertNull(tap.tap())
        now += 500_000_000; assertNull(tap.tap())
        now += 500_000_000; assertEquals(120, tap.tap())
        now += 500_000_000; assertEquals(120, tap.tap())
        // 2 sn'den uzun ara: sıfırlanır
        now += 3_000_000_000; assertNull(tap.tap())
    }
}
