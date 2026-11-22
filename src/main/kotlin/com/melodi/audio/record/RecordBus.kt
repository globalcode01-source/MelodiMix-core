package com.melodi.audio.record

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kaydın karıştırıcısı. Ekran/mikrofon kaydı yok: sesler doğrudan kaynaktan gelir.
 *  - Deck'ler: oynatıcıların ses işleme zincirinin sonundan (EQ/FX/tempo/ton uygulanmış hâli);
 *    crossfader/kanal kazancı burada uygulanır (oynatıcıda AudioTrack sesiyle uygulandığı için).
 *  - Padler / ritim: çalındıkları an örnekleri eklenir.
 * Her şey 48 kHz stereo ortak bir zaman çizgisine yerleştirilir; "şu an"dan geride kalan kısım
 * kesinleşip dosyaya yazılır.
 *
 * Zamanlama: bir kaynağın (yeniden) başladığı ilk parça hemen duyulur; sonraki parçalar
 * ardışık eklenir. Oynatıcı tampon kadar önden işlediği için parçalar "şu an"ın biraz ilerisine düşer.
 * Her kaynağın kendi şeridi vardır: seek/loop başa dönüşünde oynatıcı tamponunda bekleyip hiç
 * çalınmayan ses yalnızca o kaynağın şeridinden silinir (kayda karışmaz); şeritler yazarken toplanır.
 *
 * Kesin hiza (DJ deck'leri): her parçanın medya zamanı sayılır (motor sarma hedefini ve hızı bildirir) ve
 * motor her ~15 ms'de oynatıcının gerçekten duyulan konumunu verir. Parça, o medya zamanının duyulacağı ana
 * yerleştirilir; böylece kayıttaki deck'ler arası hiza senkron motorunun ölçtüğüyle aynı kaynağa dayanır.
 * Küçük kaymalar örnekleme oranı ±%0,4 oynatılarak fark edilmeden kapatılır.
 */
object RecordBus {
    const val RATE = Pcm.SAMPLE_RATE
    /** Ana oynatıcı (kitaplık/eski oynatıcı) */
    const val SOURCE_A = 0
    const val SOURCE_B = 1
    /** DJ deck'leri: deck i → kaynak DECK_BASE + i */
    const val DECK_BASE = 1
    /** 0 = ana oynatıcı, 1..4 = DJ deck'leri */
    const val SOURCES = 5
    /** Pad/ritim şeridi */
    private const val PADS = SOURCES
    /** Kaynaklar en fazla ~1 sn önden gelir, yazma 100 ms'de bir: 4 sn yeterli */
    private const val RING_FRAMES = RATE * 4

    private val lock = Any()
    @Volatile var active = false
        private set
    private var t0 = 0L
    /** Şerit başına stereo halka (kayıt başlarken ayrılır, bitince bırakılır) */
    private var lanes: Array<FloatArray> = emptyArray()
    private var committed = 0L

    private class Source {
        @Volatile var gain = 1f
        // Zaman çizgisindeki yazma konumu (çıkış karesi); -1 = yeniden hizala
        var cursor = -1L
        var lead = 0L
        // Örnekleme hızı dönüştürücü durumu
        var pos = 0.0
        var prevL = 0f
        var prevR = 0f
        var work = FloatArray(0)
        var out = FloatArray(0)

        // Medya zamanı izleme
        /** Bir sonraki giriş karesinin medya zamanı (ms); bilinmiyorsa NaN */
        var mediaMs = Double.NaN
        /** Gerçek kesintiden sonra akışın başlayacağı medya konumu (motor sarmadan önce bildirir) */
        @Volatile var pendingStartMs = -1L
        /** Motorun en son uyguladığı hız; zincir yeniden kurulunca (boşalt + sıfırla) geçerli olur */
        @Volatile var nextSpeed = 1.0
        var speed = 1.0
        // Duyulan konum örneği (motor iş parçacığı yazar)
        @Volatile var heardMs = Double.NaN
        @Volatile var heardAtNanos = 0L
        @Volatile var heardPlaying = false
        var sinceReset = 0L
        var slew = 0.0
        /** Son gerçek kesintinin medya başlangıcı ve o zamandan beri gelen giriş karesi */
        var lastStartMs = Double.NaN
        var inputSinceReset = 0L

        /** Medya saati: kayıt olsun olmasın her giriş parçasında ilerler. */
        fun account(frames: Int, rate: Int): Double {
            val start = mediaMs
            if (!mediaMs.isNaN()) mediaMs += frames * speed * 1000.0 / rate
            inputSinceReset += frames
            return start
        }

        /** Kesintiden sonra ilk çıkış örneği yeni parçanın ilk örneği olsun (eski sesle ara değerleme = tık). */
        fun restart() {
            pos = 1.0
            prevL = 0f
            prevR = 0f
        }
    }

    private val sources = Array(SOURCES) { Source() }
    private val oneShotCache = HashMap<Any, FloatArray>()

    fun setGain(source: Int, gain: Float) { sources[source].gain = gain }

    /** Motor bir deck'i sarmadan/yüklemeden önce: yeni akış bu medya konumundan başlayacak. */
    fun expectStart(source: Int, mediaMs: Long) { sources[source].pendingStartMs = mediaMs.coerceAtLeast(0) }

    /** Motorun bildirdiği bir sarma henüz gerçekleşmedi mi (o zaman gelen sıfırlama kesinlikle gerçek kesintidir) */
    fun seekPending(source: Int): Boolean = sources[source].pendingStartMs >= 0

    /** Motor tempo/ton uyguladığında (zincir yeniden kurulunca geçerli olur). */
    fun setSpeed(source: Int, speed: Float) { sources[source].nextSpeed = speed.toDouble() }

    /** Motorun saatinden: oynatıcının şu an gerçekten duyulan medya konumu. */
    fun heard(source: Int, positionMs: Long, playing: Boolean) {
        val s = sources[source]
        s.heardMs = positionMs.toDouble()
        s.heardAtNanos = System.nanoTime()
        s.heardPlaying = playing
    }

    /** Zincir yalnızca yeniden kuruldu (tempo değişikliği): akış kesintisiz sürer, yeni hız geçerli. */
    fun reconfigured(source: Int) {
        val s = sources[source]
        s.speed = s.nextSpeed
    }

    fun start() = synchronized(lock) {
        lanes = Array(SOURCES + 1) { FloatArray(RING_FRAMES * 2) }
        committed = 0
        sources.forEach { it.cursor = -1; it.lead = 0; it.restart() }
        t0 = System.nanoTime()
        active = true
    }

    fun stop() { active = false }

    /** Son yazımdan sonra belleği bırak. */
    fun release() {
        synchronized(lock) { if (!active) lanes = emptyArray() }
    }

    private fun nowFrame(): Long = (System.nanoTime() - t0) * RATE / 1_000_000_000L

    /**
     * Gerçek kesinti (seek, loop başa dönüşü, parça değişimi): oynatıcının tamponunda bekleyen ve artık
     * hiç çalınmayacak ses bu kaynağın şeridinden silinir; sıradaki parça "şu an"a hizalanır.
     */
    fun resetSource(source: Int) {
        val s = sources[source]
        synchronized(lock) {
            if (active && s.cursor >= 0 && lanes.isNotEmpty()) {
                val lane = lanes[source]
                var p = maxOf(nowFrame(), committed)
                while (p < s.cursor && p < committed + RING_FRAMES) {
                    val idx = ((p % RING_FRAMES) * 2).toInt()
                    lane[idx] = 0f
                    lane[idx + 1] = 0f
                    p++
                }
            }
            s.cursor = -1
            // Yeni akışın medya başlangıcı: motorun bildirdiği sarma hedefi. Arada hiç ses geçmeden ikinci bir
            // sıfırlama gelirse (ör. parça yüklenirken durdur + hazırla) aynı başlangıç geçerli kalır.
            val pending = s.pendingStartMs
            s.mediaMs = when {
                pending >= 0 -> pending.toDouble()
                s.inputSinceReset == 0L -> s.lastStartMs
                else -> Double.NaN
            }
            s.pendingStartMs = -1
            s.lastStartMs = s.mediaMs
            s.inputSinceReset = 0
            s.speed = s.nextSpeed
            s.sinceReset = 0
            s.slew = 0.0
        }
        s.restart()
    }

    /** Bir kaynağın şeridinde [from, to) aralığını sil (henüz yazılmamış kısım). */
    private fun zero(lane: FloatArray, from: Long, to: Long) {
        var p = maxOf(from, committed)
        while (p < to && p < committed + RING_FRAMES) {
            val idx = ((p % RING_FRAMES) * 2).toInt()
            lane[idx] = 0f
            lane[idx + 1] = 0f
            p++
        }
    }

    /** Oynatıcının işlediği 16 bit PCM (oynatma iş parçacığından çağrılır). */
    fun feed(source: Int, data: ByteBuffer, rate: Int, channels: Int) {
        if (channels <= 0 || rate <= 0) return
        val s = sources[source]
        val frames = data.remaining() / (2 * channels)
        if (frames == 0) return
        val chunkMediaMs = s.account(frames, rate)
        if (!active) return
        val buf = data.order(ByteOrder.LITTLE_ENDIAN)
        if (s.work.size < frames * 2) s.work = FloatArray(frames * 2)
        val w = s.work
        val base = buf.position()
        val g = s.gain / 32768f
        for (f in 0 until frames) {
            val o = base + f * channels * 2
            val l = buf.getShort(o) * g
            val r = if (channels >= 2) buf.getShort(o + 2) * g else l
            w[f * 2] = l
            w[f * 2 + 1] = r
        }

        // Doğrusal ara değerleme ile 48 kHz'e (hiza düzeltmesi için oran çok az oynatılabilir)
        val step = rate.toDouble() / RATE * (1.0 + s.slew)
        val maxOut = (frames / step).toInt() + 4
        if (s.out.size < maxOut * 2) s.out = FloatArray(maxOut * 2)
        var n = 0
        var p = s.pos // 0 = önceki parçanın son karesi
        while (p + 1 <= frames) {
            val i = p.toInt()
            val fr = (p - i).toFloat()
            val l0 = if (i == 0) s.prevL else w[(i - 1) * 2]
            val r0 = if (i == 0) s.prevR else w[(i - 1) * 2 + 1]
            val l1 = w[i * 2]
            val r1 = w[i * 2 + 1]
            s.out[n * 2] = l0 + (l1 - l0) * fr
            s.out[n * 2 + 1] = r0 + (r1 - r0) * fr
            n++
            p += step
        }
        s.pos = p - frames
        s.prevL = w[(frames - 1) * 2]
        s.prevR = w[(frames - 1) * 2 + 1]

        synchronized(lock) {
            if (!active || lanes.isEmpty()) return
            val now = nowFrame()
            // Bu parçanın duyulacağı an: medya zamanı − şu an duyulan konum (motorun saatinden)
            var ideal = Long.MIN_VALUE
            if (!chunkMediaMs.isNaN() && s.heardPlaying && !s.heardMs.isNaN()) {
                val ageMs = (System.nanoTime() - s.heardAtNanos) / 1e6
                if (ageMs < 200) {
                    val heardNow = s.heardMs + ageMs * s.speed
                    val leadMs = (chunkMediaMs - heardNow) / s.speed
                    if (leadMs > -500 && leadMs < 2_000) ideal = now + (leadMs * RATE / 1000).toLong()
                }
            }
            if (s.cursor < 0) {
                s.cursor = if (ideal != Long.MIN_VALUE) ideal else now
            } else if (ideal != Long.MIN_VALUE) {
                val err = s.cursor - ideal // > 0: geç yerleştiriliyor
                if (kotlin.math.abs(err) > RATE / 20 || (s.sinceReset < RATE * 2 / 5 && kotlin.math.abs(err) > RATE / 1000)) {
                    // Kesintiden hemen sonra ya da büyük sapmada atla; geri atlarken eski kuyruk ikilenmesin
                    if (err > 0) zero(lanes[source], ideal, s.cursor)
                    s.cursor = ideal
                    s.slew = 0.0
                } else {
                    // Küçük kayma: ~2 sn'de fark edilmeden kapat
                    s.slew = (err.toDouble() / (RATE * 2)).coerceIn(-0.004, 0.004)
                }
            } else if (s.cursor < now - RATE / 5) {
                // Motor bilgisi yok (ör. ana oynatıcı) ve duraklatma/takılma sonrası: önceki önde gitme miktarıyla
                s.cursor = now + s.lead
            }
            mixIn(lanes[source], s.out, n, s.cursor, 1f)
            s.cursor += n
            s.sinceReset += n
            s.lead = (s.cursor - now).coerceIn(0, RATE.toLong())
        }
    }

    /** Pad / ritim vuruşu: tek kanallı örnek, [srcRate] hızında; şu ana eklenir. */
    fun addOneShot(key: Any, samples: () -> FloatArray, srcRate: Int, volume: Float) {
        if (!active) return
        val mono = synchronized(oneShotCache) {
            oneShotCache.getOrPut(key to srcRate) { resampleMono(samples(), srcRate) }
        }
        synchronized(lock) {
            if (!active || lanes.isEmpty()) return
            val start = nowFrame()
            var i = 0
            val stereo = FloatArray(mono.size * 2)
            for (v in mono) { stereo[i++] = v; stereo[i++] = v }
            mixIn(lanes[PADS], stereo, mono.size, start, volume)
        }
    }

    private fun mixIn(lane: FloatArray, src: FloatArray, frames: Int, at: Long, gain: Float) {
        for (k in 0 until frames) {
            val pos = at + k
            if (pos < committed) continue
            if (pos >= committed + RING_FRAMES) break
            val idx = ((pos % RING_FRAMES) * 2).toInt()
            lane[idx] += src[k * 2] * gain
            lane[idx + 1] += src[k * 2 + 1] * gain
        }
    }

    /**
     * Şu ana kadar duyulmuş kısmı kesinleştirir (şeritleri toplar) ve 16 bit PCM olarak döndürür
     * (yazılacak bir şey yoksa boş dizi).
     */
    fun commit(): ByteArray = synchronized(lock) {
        if (lanes.isEmpty()) return ByteArray(0)
        val target = nowFrame()
        if (target <= committed) return ByteArray(0)
        val frames = (target - committed).coerceAtMost(RING_FRAMES.toLong()).toInt()
        val out = ByteArray(frames * 4)
        var o = 0
        for (k in 0 until frames) {
            val idx = (((committed + k) % RING_FRAMES) * 2).toInt()
            for (c in 0..1) {
                var sum = 0f
                for (lane in lanes) {
                    sum += lane[idx + c]
                    lane[idx + c] = 0f
                }
                val v = (softClip(sum) * 32767f).toInt()
                out[o++] = v.toByte()
                out[o++] = (v shr 8).toByte()
            }
        }
        committed += frames
        out
    }

    /** İki deck üst üste binince sert kırpılma yerine yumuşak sınırlama (0,8'in üstü). */
    internal fun softClip(x: Float): Float {
        val a = kotlin.math.abs(x)
        if (a <= 0.8f) return x
        val y = 0.8f + 0.2f * kotlin.math.tanh((a - 0.8f) / 0.2f)
        return if (x < 0) -y else y
    }

    private fun resampleMono(src: FloatArray, srcRate: Int): FloatArray {
        if (srcRate == RATE) return src
        val step = srcRate.toDouble() / RATE
        val n = ((src.size - 1) / step).toInt()
        return FloatArray(n) { i ->
            val p = i * step
            val j = p.toInt()
            val f = (p - j).toFloat()
            src[j] + (src[minOf(j + 1, src.lastIndex)] - src[j]) * f
        }
    }
}
