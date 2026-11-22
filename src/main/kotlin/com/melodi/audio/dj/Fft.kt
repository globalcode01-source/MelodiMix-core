package com.melodi.audio.dj

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Yerinde, radix-2 FFT (analiz için; gerçek zamanlı ses yolunda kullanılmaz). */
class Fft(val size: Int) {
    init {
        require(size > 0 && size and (size - 1) == 0) { "FFT boyutu 2'nin kuvveti olmalı" }
    }

    private val cosT = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinT = DoubleArray(size / 2) { -sin(2 * PI * it / size) }
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / (size - 1))).toFloat() } // Hann
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)

    /** [input]'un [offset]'ten başlayan [size] örneğinin genlik spektrumu (size/2 kutu) → [out]. */
    fun magnitudes(input: FloatArray, offset: Int, out: FloatArray) {
        for (i in 0 until size) {
            val k = offset + i
            re[i] = (if (k in input.indices) input[k] else 0f).toDouble() * window[i]
            im[i] = 0.0
        }
        transform()
        for (i in 0 until size / 2) out[i] = sqrt(re[i] * re[i] + im[i] * im[i]).toFloat()
    }

    private fun transform() {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val step = size / len
            for (i in 0 until size step len) {
                for (k in 0 until len / 2) {
                    val wr = cosT[k * step]
                    val wi = sinT[k * step]
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
            }
            len = len shl 1
        }
    }
}
