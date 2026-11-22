package com.melodi.audio.record

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Kaydın ham ses biçimi: 48 kHz, stereo, 16 bit (Opus 48 kHz istediği için). */
object Pcm {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val BYTES_PER_FRAME = CHANNELS * 2

    fun durationMs(bytes: Long): Long = bytes / BYTES_PER_FRAME * 1000 / SAMPLE_RATE

    /** 44 baytlık WAV başlığı (little-endian PCM). */
    fun wavHeader(dataBytes: Long, sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS): ByteArray {
        val byteRate = sampleRate * channels * 2
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        b.putInt(sampleRate).putInt(byteRate).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }

    /** Ham PCM dosyasını WAV'a çevirir. */
    fun writeWav(pcm: File, out: OutputStream) {
        out.write(wavHeader(pcm.length()))
        pcm.inputStream().use { it.copyTo(out, 64 * 1024) }
    }

    /** Akıştan tam [buf] kadar (dosya sonunda daha az) okur; okunan bayt sayısını döndürür. */
    fun readFully(input: InputStream, buf: ByteArray, len: Int = buf.size): Int {
        var n = 0
        while (n < len) {
            val r = input.read(buf, n, len - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** 16 bit örneklerin tepe seviyesi, 0..1 (kayıt göstergesi için). */
    fun peak(buf: ByteArray, len: Int): Float {
        var max = 0
        var i = 0
        while (i + 1 < len) {
            val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
            val a = kotlin.math.abs(s.toShort().toInt())
            if (a > max) max = a
            i += 2
        }
        return max / 32768f
    }
}
