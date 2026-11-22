package com.melodi.audio.dj

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Bir parçanın analiz sonucu. Bulunamayan değerler null (arayüzde "bilinmiyor"). */
data class AnalysisResult(
    val bpm: Float?,
    /** İlk vuruşun zamanı (ms); vuruş ızgarası bpm ile birlikte bundan türetilir. */
    val firstBeatMs: Double?,
    val key: MusicalKey?,
    val energy: Int?,
    val introEndMs: Long?,
    val outroStartMs: Long?,
    val dropMs: Long?,
    /** Genel bakış dalga formu: kova başına tepe (0–255) */
    val wavePeaks: ByteArray,
    /** Kova başına bas oranı (0–255): dalga formunu renklendirmek için */
    val waveLow: ByteArray,
    val durationMs: Long,
    /** BPM/ton nereden geldi: "analysis", "metadata" */
    val bpmSource: String? = null,
    val keySource: String? = null,
) {
    val beatMs: Double? get() = bpm?.let { 60_000.0 / it }
}

/**
 * Akış hâlinde ses analizi (mono, ~11 kHz). Kod çözücüden parça parça beslenir, sonda sonuç üretir.
 * Saf Kotlin: Android'e bağlı değil, JVM'de birim testleriyle doğrulanıyor.
 *
 *  - BPM: spektral akı (onset) zarfının otokorelasyonu + tüm parça boyunca vuruş ızgarası uydurma
 *  - Ton: kromagram + Krumhansl–Kessler majör/minör profilleri
 *  - Enerji: ses yüksekliği (RMS), ritmik yoğunluk ve parlaklık
 *  - Intro/outro/drop: yumuşatılmış enerji eğrisinden tahmin
 * Güven düşükse sonuç null döner; yanlış değer kesinmiş gibi gösterilmez.
 */
class AnalysisAccumulator(val sampleRate: Int, val durationMs: Long, private val buckets: Int = 800) {
    private val totalSamples = (durationMs * sampleRate / 1000).coerceAtLeast(1)
    private var count = 0L

    // Dalga formu
    private val peaks = FloatArray(buckets)
    private val lowSum = FloatArray(buckets)
    private val allSum = FloatArray(buckets)
    private var lp = 0f
    private val lpA = 1f - exp(-2.0 * Math.PI * 200 / sampleRate).toFloat()

    // Onset (spektral akı)
    private val hop = 256
    private val fftSize = 1024
    private val fft = Fft(fftSize)
    private val ring = FloatArray(fftSize)
    private var ringPos = 0
    private var sinceHop = 0
    private val mag = FloatArray(fftSize / 2)
    private var prevLog = FloatArray(fftSize / 2)
    private val onsets = FloatList()
    /** Yalnızca bas bandı (< ~160 Hz) akısı: vuruş fazını kick'e kilitlemek için (aradaki hi-hat'lere değil) */
    private val lowOnsets = FloatList()
    private val lowBins = (160.0 * fftSize / sampleRate).toInt().coerceAtLeast(2)
    private var brightSum = 0.0
    private var specSum = 0.0

    // Kromagram (daha ince frekans çözünürlüğü için ayrı, büyük pencere)
    private val chromaFftSize = 4096
    private val chromaFft = Fft(chromaFftSize)
    private val chromaRing = FloatArray(chromaFftSize)
    private var chromaPos = 0
    private var sinceChroma = 0
    private val chromaMag = FloatArray(chromaFftSize / 2)
    private val chroma = DoubleArray(12)
    private val chromaBinPc = IntArray(chromaFftSize / 2) { k ->
        val f = k.toDouble() * sampleRate / chromaFftSize
        if (f < 65 || f > 2100) -1 else (((12 * log2(f / 440.0) + 69).roundToInt() % 12) + 12) % 12
    }

    // Ses yüksekliği (1 sn pencereler)
    private var sq = 0.0
    private var winSq = 0.0
    private var winN = 0
    private val windows = FloatList()

    fun push(x: FloatArray, n: Int) {
        for (i in 0 until n) {
            val s = x[i]
            val b = ((count * buckets) / totalSamples).toInt().coerceIn(0, buckets - 1)
            val a = abs(s)
            if (a > peaks[b]) peaks[b] = a
            lp += lpA * (s - lp)
            lowSum[b] += lp * lp
            allSum[b] += s * s
            sq += s * s
            winSq += s * s
            if (++winN == sampleRate) {
                windows.add(sqrt(winSq / winN).toFloat())
                winSq = 0.0; winN = 0
            }

            ring[ringPos] = s
            ringPos = (ringPos + 1) % fftSize
            if (++sinceHop == hop) { sinceHop = 0; onsetFrame() }

            chromaRing[chromaPos] = s
            chromaPos = (chromaPos + 1) % chromaFftSize
            if (++sinceChroma == chromaFftSize / 2) { sinceChroma = 0; chromaFrame() }
            count++
        }
    }

    private val frame = FloatArray(fftSize)
    private fun onsetFrame() {
        for (i in 0 until fftSize) frame[i] = ring[(ringPos + i) % fftSize]
        fft.magnitudes(frame, 0, mag)
        var flux = 0f
        var lowFlux = 0f
        val cur = FloatArray(mag.size)
        val brightFrom = (2000.0 * fftSize / sampleRate).toInt()
        for (k in 1 until mag.size) {
            val l = ln(1f + 100f * mag[k])
            cur[k] = l
            val d = l - prevLog[k]
            if (d > 0) {
                flux += d
                if (k <= lowBins) lowFlux += d
            }
            specSum += mag[k]
            if (k >= brightFrom) brightSum += mag[k]
        }
        prevLog = cur
        onsets.add(flux)
        lowOnsets.add(lowFlux)
    }

    private val chromaFrameBuf = FloatArray(chromaFftSize)
    private fun chromaFrame() {
        for (i in 0 until chromaFftSize) chromaFrameBuf[i] = chromaRing[(chromaPos + i) % chromaFftSize]
        chromaFft.magnitudes(chromaFrameBuf, 0, chromaMag)
        val c = DoubleArray(12)
        var total = 0.0
        for (k in chromaMag.indices) {
            val pc = chromaBinPc[k]
            if (pc < 0) continue
            val v = sqrt(chromaMag[k].toDouble())
            c[pc] += v
            total += v
        }
        if (total <= 1e-9) return
        for (i in 0 until 12) chroma[i] += c[i] / total // kare başına normalize: yüksek bölümler baskın olmasın
    }

    fun finish(): AnalysisResult {
        val env = onsets.toArray()
        val hopMs = hop * 1000.0 / sampleRate
        val beat = estimateTempo(env, hopMs, lowOnsets.toArray(), lowBins.toDouble() / (fftSize / 2 - 1))
        val key = estimateKey(chroma)
        val rms = sqrt(sq / count.coerceAtLeast(1))
        val bright = if (specSum > 0) (brightSum / specSum * 3).coerceIn(0.0, 1.0) else null
        val energy = if (count < sampleRate * 5L) null else estimateEnergy(rms, env, hopMs, bright)
        val sections = estimateSections(windows.toArray(), beat)

        val maxPeak = peaks.maxOrNull()?.takeIf { it > 0 } ?: 1f
        val wave = ByteArray(buckets) { (peaks[it] / maxPeak * 255).roundToInt().coerceIn(0, 255).toByte() }
        val low = ByteArray(buckets) {
            val r = if (allSum[it] > 0) lowSum[it] / allSum[it] else 0f
            (sqrt(r) * 255).roundToInt().coerceIn(0, 255).toByte()
        }
        return AnalysisResult(
            bpm = beat?.first, firstBeatMs = beat?.second, key = key, energy = energy,
            introEndMs = sections.first, outroStartMs = sections.second, dropMs = sections.third,
            wavePeaks = wave, waveLow = low, durationMs = durationMs,
            bpmSource = beat?.let { "analysis" }, keySource = key?.let { "analysis" },
        )
    }

    companion object {
        const val MIN_BPM = 70f
        const val MAX_BPM = 180f

        /**
         * Tempo ve ilk vuruş: (bpm, ilk vuruş ms). Güven düşükse null.
         * 1) Onset zarfı yerel ortalamadan arındırılır, 2) otokorelasyon + log-normal tempo önceli ile
         * kaba BPM, 3) ±3 BPM içinde 0,02 adımla tüm parça boyunca vuruş ızgarası uydurulur (faz dahil).
         */
        fun estimateTempo(raw: FloatArray, hopMs: Double, low: FloatArray? = null, lowBinFraction: Double = 0.03): Pair<Float, Double>? {
            if (raw.size < 400) return null // < ~10 sn
            val w = (500 / hopMs).toInt().coerceAtLeast(1)
            val env = detrend(raw, w)
            val lagMin = (60_000 / (MAX_BPM * 1.05) / hopMs).toInt()
            val lagMax = (60_000 / (MIN_BPM * 0.95) / hopMs).toInt() + 1
            val acf = DoubleArray(lagMax * 2 + 2)
            for (lag in 0 until acf.size) {
                var s = 0.0
                var i = 0
                val n = env.size - lag
                while (i < n) { s += env[i] * env[i + lag]; i++ }
                acf[lag] = s / maxOf(n, 1)
            }
            if (acf[0] <= 0) return null
            var best = -1
            var bestScore = 0.0
            for (lag in lagMin..lagMax) {
                val bpm = 60_000 / (lag * hopMs)
                val prior = exp(-0.5 * (log2(bpm / 122.0) / 0.5).let { it * it })
                val s = (acf[lag] + 0.5 * acf[lag * 2]) * prior
                if (s > bestScore) { bestScore = s; best = lag }
            }
            if (best < 0) return null
            // Belirginlik: tepe, aralıktaki ortalamanın belirgin üstünde olmalı
            val meanAcf = (lagMin..lagMax).sumOf { acf[it] } / (lagMax - lagMin + 1)
            if (acf[best] < meanAcf * 1.25 || acf[best] / acf[0] < 0.04) return null
            // Parabolik ara değerleme: tam sayı gecikme aralığı tek başına ~±4 BPM kaba
            val y0 = acf[best - 1]; val y1 = acf[best]; val y2 = acf[best + 1]
            val denom = y0 - 2 * y1 + y2
            val frac = if (denom < 0) (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5) else 0.0
            var coarse = (60_000 / ((best + frac) * hopMs)).toFloat()
            while (coarse < MIN_BPM) coarse *= 2
            while (coarse > MAX_BPM) coarse /= 2

            // Faz için zarf: tüm bant + ağırlıklı bas bandı. House gibi türlerde ara vuruştaki hi-hat'ler
            // geniş bantta kick'ten güçlü görünür; bas bandı ızgarayı asıl vuruşa (kick) çeker.
            // Bas bandı yalnızca parçada gerçekten bas vuruşu varsa ağırlıklandırılır: basın neredeyse hiç olmadığı
            // parçalarda (akustik, vokal) onu kendi ortalamasına bölmek gürültüyü büyütür ve fazı bozar.
            val phaseEnv = if (low != null && low.size == raw.size) {
                val lowEnv = detrend(low, w)
                val mFull = env.average().coerceAtLeast(1e-9)
                val mLow = lowEnv.average()
                // Kutu başına bas akısı / kutu başına tüm akı: bas vuruşlu müzikte ≫ 1, bassız parçada ≪ 1
                val perBin = (mLow / mFull) / lowBinFraction
                val weight = (2.0 * ((perBin - 0.3) / 0.7)).coerceIn(0.0, 2.0)
                if (weight <= 0.0 || mLow <= 1e-9) env
                else FloatArray(env.size) { (env[it] / mFull + weight * lowEnv[it] / mLow).toFloat() }
            } else env

            // İnce ayar: vuruş ızgarası uydurma
            var bestBpm = coarse
            var bestPhase = 0.0
            var bestFit = -1.0
            var b = coarse - 3f
            while (b <= coarse + 3f) {
                val period = 60_000.0 / b / hopMs
                var ph = 0.0
                while (ph < period) {
                    var s = 0.0
                    var t = ph
                    var k = 0
                    while (t < phaseEnv.size - 1) {
                        val i = t.toInt()
                        val f = t - i
                        s += phaseEnv[i] * (1 - f) + phaseEnv[i + 1] * f
                        t += period; k++
                    }
                    val fit = s / maxOf(k, 1)
                    if (fit > bestFit) { bestFit = fit; bestBpm = b; bestPhase = ph }
                    ph += 0.5
                }
                b += 0.02f
            }
            val rounded = bestBpm.roundToInt().toFloat()
            val bpm = if (abs(bestBpm - rounded) < 0.08f) rounded else (bestBpm * 100).roundToInt() / 100f
            // Akı, olay pencerenin son çeyreğine girerken en yüksek: kare i ≈ örnek i·hop
            val firstBeat = bestPhase * hopMs
            return bpm to firstBeat
        }

        /** Yerel ortalamayı çıkar, negatifleri sıfırla (yalnızca ani artışlar kalsın). */
        private fun detrend(raw: FloatArray, w: Int): FloatArray {
            val env = FloatArray(raw.size)
            var run = 0.0
            for (i in raw.indices) {
                run += raw[i]
                if (i >= w) run -= raw[i - w]
                val mean = run / minOf(i + 1, w)
                env[i] = (raw[i] - mean).toFloat().coerceAtLeast(0f)
            }
            return env
        }

        // Krumhansl–Kessler ton profilleri
        private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

        /** En iyi ton belirgin değilse null. */
        fun estimateKey(chroma: DoubleArray): MusicalKey? {
            if (chroma.sum() <= 0) return null
            val scores = mutableListOf<Pair<MusicalKey, Double>>()
            for (pc in 0 until 12) {
                scores += MusicalKey(pc, false) to pearson(chroma, MAJOR, pc)
                scores += MusicalKey(pc, true) to pearson(chroma, MINOR, pc)
            }
            scores.sortByDescending { it.second }
            val (top, r) = scores[0]
            val second = scores[1].second
            return if (r >= 0.5 && r - second >= 0.02) top else null
        }

        private fun pearson(c: DoubleArray, profile: DoubleArray, shift: Int): Double {
            val p = DoubleArray(12) { profile[(it - shift + 12) % 12] }
            val mc = c.average()
            val mp = p.average()
            var num = 0.0; var dc = 0.0; var dp = 0.0
            for (i in 0 until 12) {
                num += (c[i] - mc) * (p[i] - mp)
                dc += (c[i] - mc) * (c[i] - mc)
                dp += (p[i] - mp) * (p[i] - mp)
            }
            return if (dc <= 0 || dp <= 0) 0.0 else num / sqrt(dc * dp)
        }

        /** 0–100 tahmini enerji: ses yüksekliği %55, ritmik yoğunluk %30, parlaklık %15. */
        fun estimateEnergy(rms: Double, env: FloatArray, hopMs: Double, brightness: Double? = null): Int {
            val db = 20 * log10(rms.coerceAtLeast(1e-6))
            val loud = ((db + 30) / 20).coerceIn(0.0, 1.0) // -30 dBFS → 0, -10 dBFS → 1
            val sorted = env.sorted()
            val median = sorted.getOrElse(sorted.size / 2) { 0f }
            val strong = env.count { it > median * 1.8f && it > 0f }
            val perSecond = strong / (env.size * hopMs / 1000.0)
            val density = (perSecond / 6.0).coerceIn(0.0, 1.0)
            val bright = brightness ?: density
            return (100 * (0.55 * loud + 0.30 * density + 0.15 * bright)).roundToInt().coerceIn(0, 100)
        }

        /**
         * Intro sonu, outro başı ve drop (ms) — 1 sn RMS pencerelerinden. Belirgin değilse null.
         * BPM biliniyorsa vuruş ızgarasına yuvarlanır.
         */
        fun estimateSections(win: FloatArray, beat: Pair<Float, Double>?): Triple<Long?, Long?, Long?> {
            if (win.size < 30) return Triple(null, null, null)
            val smooth = FloatArray(win.size) { i ->
                var s = 0f; var n = 0
                for (k in maxOf(0, i - 2)..minOf(win.lastIndex, i + 2)) { s += win[k]; n++ }
                s / n
            }
            val p80 = smooth.sorted()[(smooth.size * 0.8).toInt()]
            if (p80 <= 0f) return Triple(null, null, null)
            val th = p80 * 0.6f
            val first = smooth.indexOfFirst { it >= th }
            val last = smooth.indexOfLast { it >= th }
            val intro = first.takeIf { it >= 4 }?.let { it * 1000L }
            val outro = last.takeIf { win.size - it >= 8 }?.let { (it + 1) * 1000L }
            var drop: Long? = null
            var bestJump = p80 * 0.3f
            for (i in maxOf(first, 8) until win.size) {
                val j = smooth[i] - smooth[i - 8]
                if (j > bestJump) { bestJump = j; drop = i * 1000L }
            }
            fun snap(ms: Long?): Long? {
                if (ms == null || beat == null) return ms
                val period = 60_000.0 / beat.first
                val k = ((ms - beat.second) / period).roundToInt()
                return (beat.second + k * period).toLong().coerceAtLeast(0)
            }
            return Triple(snap(intro), snap(outro), snap(drop))
        }
    }
}

/** Büyüyen float dizisi (kutulama olmadan). */
internal class FloatList(initial: Int = 4096) {
    private var data = FloatArray(initial)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
