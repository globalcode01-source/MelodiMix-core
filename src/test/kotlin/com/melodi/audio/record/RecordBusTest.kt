package com.melodi.audio.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class RecordBusTest {
    private fun pcm16(rate: Int, seconds: Double, hz: Double, amp: Int = 12000): ByteBuffer {
        val frames = (rate * seconds).toInt()
        val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * hz * i / rate) * amp).toInt().toShort()
            b.putShort(v).putShort(v)
        }
        b.flip()
        return b
    }

    private fun peakOf(bytes: ByteArray) = Pcm.peak(bytes, bytes.size)

    @Test
    fun feedIsResampledMixedAndCommitted() {
        RecordBus.start()
        RecordBus.setGain(RecordBus.SOURCE_A, 1f)
        RecordBus.feed(RecordBus.SOURCE_A, pcm16(44_100, 0.5, 440.0), 44_100, 2)
        Thread.sleep(300)
        val out = RecordBus.commit()
        RecordBus.stop()
        assertTrue("çıktı ${out.size}", out.size >= 48_000 * 4 / 4)
        assertEquals(12000 / 32768f, peakOf(out), 0.03f)
    }

    @Test
    fun crossfaderGainIsApplied() {
        RecordBus.start()
        RecordBus.setGain(RecordBus.SOURCE_B, 0.5f)
        RecordBus.feed(RecordBus.SOURCE_B, pcm16(48_000, 0.5, 440.0), 48_000, 2)
        Thread.sleep(250)
        val out = RecordBus.commit()
        RecordBus.stop()
        RecordBus.setGain(RecordBus.SOURCE_B, 1f)
        assertEquals(6000 / 32768f, peakOf(out), 0.02f)
    }
}

class RecordBusLaneTest {
    private fun pcm(frames: Int, v: Short): ByteBuffer {
        val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { b.putShort(v).putShort(v) }
        b.flip()
        return b
    }

    /** Seek/loop: tamponda bekleyip hiç çalınmayan ses kayda karışmamalı. */
    @Test
    fun resetDiscardsUnplayedAudioOfThatSourceOnly() {
        // Tamponlar kayıt başlamadan hazır: yük altında ikinci tamponu üretmek kaynak 2'yi on milisaniyelerce
        // geciktiriyor, "baştaki toplam" noktasına yetişemiyordu (test ara sıra düşüyordu)
        val one = pcm(48_000, 16000)
        val two = pcm(48_000, 8000)
        RecordBus.start()
        RecordBus.setGain(1, 1f)
        RecordBus.setGain(2, 1f)
        // Kaynak 1: ~1 sn önden ses (henüz çalınmadı), kaynak 2: aynı anda sabit ses
        RecordBus.feed(1, one, 48_000, 2)
        RecordBus.feed(2, two, 48_000, 2)
        Thread.sleep(100)
        RecordBus.resetSource(1) // gerçek kesinti: kaynak 1'in çalınmamış kısmı silinmeli
        Thread.sleep(300)
        val out = RecordBus.commit()
        RecordBus.stop()
        RecordBus.release()
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val frames = out.size / 4
        // Baştaki ~100 ms iki kaynağın toplamı, sonrası yalnız kaynak 2
        val early = b.getShort(4 * (frames / 10)).toInt()
        val late = b.getShort(4 * (frames - 10)).toInt()
        assertTrue("başta toplam ($early)", early > 20000)
        assertEquals(8000f, late.toFloat(), 400f)
    }
}

class RecordBusAlignTest {
    private fun pcm(frames: Int, v: Short): ByteBuffer {
        val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { b.putShort(v).putShort(v) }
        b.flip()
        return b
    }

    /** Motor "aslında 50 ms geride duyuluyor" derse sonraki parça kayıtta 50 ms sonraya yerleşmeli. */
    @Test
    fun placesAudioWhereTheEngineSaysItIsHeard() {
        val src = 2
        RecordBus.start()
        RecordBus.setGain(src, 1f)
        RecordBus.setSpeed(src, 1f)
        RecordBus.expectStart(src, 10_000)
        RecordBus.resetSource(src) // sarmanın yarattığı gerçek kesinti
        RecordBus.heard(src, 10_000, true) // şu an 10. saniye duyuluyor
        val t1 = System.nanoTime()
        RecordBus.feed(src, pcm(4_800, 8_000), 48_000, 2) // medya 10,0–10,1 sn → hemen
        RecordBus.heard(src, 9_950, true) // gerçekte 50 ms geride
        val t2 = System.nanoTime()
        RecordBus.feed(src, pcm(4_800, 8_000), 48_000, 2) // medya 10,1–10,2 sn → ~150 ms sonra
        // Kayıt saati gerçek zamanla işliyor: iki çağrı arasında geçen süre (makine yoğunsa birkaç ms) boşluğa eklenir
        val elapsedMs = (t2 - t1) / 1e6
        Thread.sleep(400)
        val out = RecordBus.commit()
        RecordBus.stop()
        RecordBus.release()
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val frames = out.size / 4
        val loud = BooleanArray(frames) { abs(b.getShort(it * 4).toInt()) > 4_000 }
        // Kayıt saati start()'tan beri işliyor; makine yoğunsa ilk parça birkaç ms sonra başlar
        val firstStart = (0 until frames).first { loud[it] }
        val firstEnd = (firstStart until frames).first { !loud[it] }
        val secondStart = (firstEnd until frames).first { loud[it] }
        val gapMs = (secondStart - firstEnd) / 48.0
        assertEquals("ilk parçanın süresi", 100.0, (firstEnd - firstStart) / 48.0, 8.0)
        assertEquals("motorun bildirdiği 50 ms'lik kayma", 50.0 + elapsedMs, gapMs, 6.0)
    }
}

class SoftClipTest {
    @Test
    fun softClipIsTransparentBelowThresholdAndBounded() {
        assertEquals(0.5f, RecordBus.softClip(0.5f), 0f)
        assertEquals(-0.8f, RecordBus.softClip(-0.8f), 0f)
        assertTrue(RecordBus.softClip(3f) <= 1f)
        assertTrue(RecordBus.softClip(0.9f) in 0.85f..0.9f)
    }
}
