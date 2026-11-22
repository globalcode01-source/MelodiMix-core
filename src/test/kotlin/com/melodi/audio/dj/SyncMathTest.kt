package com.melodi.audio.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMathTest {
    @Test
    fun tempoMatchingIncludesHalfAndDouble() {
        assertEquals(128f / 120f, SyncMath.syncSpeed(128f, 120f), 1e-4f)
        assertEquals(1f, SyncMath.syncSpeed(128f, 64f), 1e-4f) // 64 → 128 ızgarası
        assertEquals(1f, SyncMath.syncSpeed(70f, 140f), 1e-4f)
        assertEquals(126f / 124f, SyncMath.syncSpeed(126f, 62f), 1e-4f)
    }

    @Test
    fun phaseAndError() {
        // 120 BPM: vuruş 500 ms; ilk vuruş 100 ms
        assertEquals(0.0, SyncMath.phase(600.0, 100.0, 120f), 1e-9)
        assertEquals(0.5, SyncMath.phase(850.0, 100.0, 120f), 1e-9)
        assertEquals(0.25f, SyncMath.phaseError(0.5, 0.25), 1e-6f)
        assertEquals(-0.2f, SyncMath.phaseError(0.9, 0.1), 1e-6f) // en kısa yoldan
        assertEquals(0.2f, SyncMath.phaseError(0.1, 0.9), 1e-6f)
    }

    @Test
    fun correctionIsSteppedBoundedAndHysteretic() {
        assertEquals(1f, SyncMath.correction(5f), 0f) // 6 ms altı: düzeltme yok
        assertEquals(1.005f, SyncMath.correction(20f), 1e-6f) // deck geride: biraz hızlan
        assertEquals(0.985f, SyncMath.correction(-60f), 1e-6f) // deck önde: yavaşla
        assertEquals(1.03f, SyncMath.correction(1000f), 1e-6f)
        assertEquals(0.97f, SyncMath.correction(-1000f), 1e-6f)
        // Devredeyken 3 ms'ye kadar sürer (sık aç/kapa yok)
        assertEquals(1.005f, SyncMath.correction(5f, previous = 1.005f), 1e-6f)
        assertEquals(1f, SyncMath.correction(2f, previous = 1.005f), 0f)
        assertTrue(SyncMath.correction(30f) > 1f)
    }
}
