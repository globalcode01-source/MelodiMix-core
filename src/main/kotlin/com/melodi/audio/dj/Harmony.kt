package com.melodi.audio.dj

import kotlin.math.abs
import kotlin.math.roundToInt

/** Müzikal ton: perde sınıfı (C = 0 … B = 11) ve majör/minör. */
data class MusicalKey(val pitchClass: Int, val minor: Boolean) {
    val name: String get() = NAMES[pitchClass] + if (minor) "m" else ""

    /** Camelot numarası (1–12); minör = A, majör = B. */
    val camelotNumber: Int get() {
        val n = (7 * pitchClass + if (minor) 5 else 8) % 12
        return if (n == 0) 12 else n
    }

    val camelot: String get() = "$camelotNumber${if (minor) "A" else "B"}"

    companion object {
        val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        private val FLATS = mapOf("Db" to "C#", "Eb" to "D#", "Gb" to "F#", "Ab" to "G#", "Bb" to "A#", "Cb" to "B", "Fb" to "E")

        /** "Am", "F#m", "C# minor", "Ebm", "8A", "11B" gibi yazımları çözer; anlaşılmazsa null. */
        fun parse(raw: String?): MusicalKey? {
            val s = raw?.trim()?.replace("♯", "#")?.replace("♭", "b") ?: return null
            if (s.isEmpty()) return null
            Regex("""^(\d{1,2})\s*([ABab])$""").matchEntire(s)?.let { m ->
                val n = m.groupValues[1].toInt()
                if (n !in 1..12) return null
                val minor = m.groupValues[2].equals("A", true)
                return (0 until 12).map { MusicalKey(it, minor) }.first { it.camelotNumber == n }
            }
            val m = Regex("""^([A-Ga-g])([#b]?)\s*(m|min|minor|maj|major|M)?$""").matchEntire(s) ?: return null
            var note = m.groupValues[1].uppercase() + m.groupValues[2]
            note = FLATS[note] ?: note
            val pc = NAMES.indexOf(note).takeIf { it >= 0 } ?: return null
            val q = m.groupValues[3]
            val minor = q == "m" || q.startsWith("min")
            return MusicalKey(pc, minor)
        }
    }
}

enum class Compat(val label: String) { EXCELLENT("Mükemmel"), POSSIBLE("Olası"), DIFFICULT("Zor"), UNKNOWN("Bilinmiyor") }

/** Bir parçanın eşleştirmede kullanılan özellikleri; bilinmeyenler null. */
data class TrackFeatures(
    val id: String,
    val bpm: Float? = null,
    val key: MusicalKey? = null,
    val energy: Int? = null,
    val genre: String? = null,
    val durationMs: Long = 0,
    /** Başlangıçtaki sakin bölümün uzunluğu (geçiş için), bilinmiyorsa null. */
    val introMs: Long? = null,
)

/** AI DJ yönlendirmeleri: önerilerin puanlarını değiştirir. */
enum class AssistMode(val label: String) {
    NONE("Dengeli"), ENERGY_UP("Enerji ↑"), ENERGY_DOWN("Enerji ↓"), MORE_CHILL("Daha sakin"), FASTER("Daha hızlı"), SIMILAR("Benzer")
}

data class MatchScore(
    val total: Int,
    val bpm: Float,
    val key: Float,
    val energy: Float,
    val genre: Float,
    val transition: Float,
    val bpmCompat: Compat,
    val keyCompat: Compat,
)

enum class SuggestionKind(val label: String) { BEST("En iyi eşleşme"), SAFE("Güvenli geçiş"), WILDCARD("Deneysel") }

data class Suggestion(val track: TrackFeatures, val score: MatchScore, val kind: SuggestionKind)

/**
 * Harmonik mix ve "Smart Match" puanlaması. Saf fonksiyonlar (birim testli).
 * Ağırlıklar: BPM %35, ton %30, enerji %20, tür %10, geçiş uygunluğu %5.
 * Bilinmeyen değerler nötr (0,5) sayılır; uydurma bir kesinlik verilmez.
 */
object Harmony {
    const val W_BPM = 0.35f
    const val W_KEY = 0.30f
    const val W_ENERGY = 0.20f
    const val W_GENRE = 0.10f
    const val W_TRANSITION = 0.05f
    private const val NEUTRAL = 0.5f

    /** Yarım/çift tempo dahil en yakın BPM farkı (ör. 64 ↔ 128 → 0). */
    fun bpmDistance(a: Float, b: Float): Float = listOf(b, b * 2, b / 2).minOf { abs(a - it) }

    fun bpmCompat(a: Float?, b: Float?): Compat {
        if (a == null || b == null) return Compat.UNKNOWN
        val d = bpmDistance(a, b)
        return when {
            d <= 2f -> Compat.EXCELLENT
            d <= 8f -> Compat.POSSIBLE
            else -> Compat.DIFFICULT
        }
    }

    /** 0–2 BPM mükemmel, 2–5 iyi, 5–8 olası, 8+ zor. */
    fun bpmScore(a: Float?, b: Float?): Float {
        if (a == null || b == null) return NEUTRAL
        val d = bpmDistance(a, b)
        return when {
            d <= 2f -> 1f - d * 0.025f // 1,00 → 0,95
            d <= 5f -> 0.95f - (d - 2f) * 0.05f // → 0,80
            d <= 8f -> 0.80f - (d - 5f) * 0.1f // → 0,50
            else -> (0.5f - (d - 8f) * 0.04f).coerceAtLeast(0f)
        }
    }

    /** Camelot çarkı: aynı = 1, ±1 aynı harf = 0,95, aynı numara diğer harf = 0,85, … */
    fun keyScore(a: MusicalKey?, b: MusicalKey?): Float {
        if (a == null || b == null) return NEUTRAL
        val da = wheelDistance(a.camelotNumber, b.camelotNumber)
        val sameLetter = a.minor == b.minor
        return when {
            da == 0 && sameLetter -> 1f
            da == 1 && sameLetter -> 0.95f
            da == 0 -> 0.85f
            da == 1 -> 0.6f // çapraz (ör. 8A → 9B)
            da == 2 && sameLetter -> 0.55f // +2 "enerji sıçraması"
            da == 7 && sameLetter -> 0.5f // yarım ton yukarı geçiş
            else -> (0.35f - da * 0.04f).coerceAtLeast(0.05f)
        }
    }

    fun keyCompat(a: MusicalKey?, b: MusicalKey?): Compat {
        if (a == null || b == null) return Compat.UNKNOWN
        val s = keyScore(a, b)
        return when {
            s >= 0.85f -> Compat.EXCELLENT
            s >= 0.5f -> Compat.POSSIBLE
            else -> Compat.DIFFICULT
        }
    }

    private fun wheelDistance(a: Int, b: Int): Int {
        val d = abs(a - b) % 12
        return minOf(d, 12 - d)
    }

    fun energyTarget(current: Int?, mode: AssistMode): Int? = current?.let {
        (
            it + when (mode) {
                AssistMode.ENERGY_UP -> 10
                AssistMode.ENERGY_DOWN -> -10
                AssistMode.MORE_CHILL -> -15
                AssistMode.SIMILAR -> 0
                else -> 3 // set genelde yavaşça yükselir
            }
            ).coerceIn(0, 100)
    }

    fun energyScore(target: Int?, candidate: Int?): Float {
        if (target == null || candidate == null) return NEUTRAL
        return (1f - abs(candidate - target) / 40f).coerceIn(0f, 1f)
    }

    fun genreScore(a: String?, b: String?): Float {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return NEUTRAL
        return if (ta.intersect(tb).isNotEmpty()) 1f else 0.2f
    }

    private fun tokens(g: String?): Set<String> =
        g?.lowercase()?.split(Regex("[^\\p{L}\\p{N}]+"))?.filter { it.length > 1 }?.toSet().orEmpty()

    /** Girişi olan (en az ~8 vuruş sakin başlayan) parçalar geçiş için daha uygundur. */
    fun transitionScore(c: TrackFeatures): Float {
        val intro = c.introMs ?: return NEUTRAL
        val beat = c.bpm?.let { 60_000f / it } ?: 500f
        return if (intro >= beat * 8) 1f else 0.6f
    }

    fun match(current: TrackFeatures, candidate: TrackFeatures, mode: AssistMode = AssistMode.NONE): MatchScore {
        var bpm = bpmScore(current.bpm, candidate.bpm)
        if (mode == AssistMode.FASTER && current.bpm != null && candidate.bpm != null) {
            // Daha hızlı: hedef ~%4 üstü; yavaş adaylar cezalanır.
            bpm = bpmScore(current.bpm * 1.04f, candidate.bpm)
        }
        if (mode == AssistMode.MORE_CHILL && current.bpm != null && candidate.bpm != null && candidate.bpm > current.bpm + 2) bpm *= 0.7f
        val key = keyScore(current.key, candidate.key)
        val energy = energyScore(energyTarget(current.energy, mode), candidate.energy)
        val genre = genreScore(current.genre, candidate.genre)
        val transition = transitionScore(candidate)
        val total = 100f * (W_BPM * bpm + W_KEY * key + W_ENERGY * energy + W_GENRE * genre + W_TRANSITION * transition)
        return MatchScore(
            total.roundToInt().coerceIn(0, 100), bpm, key, energy, genre, transition,
            bpmCompat(current.bpm, candidate.bpm), keyCompat(current.key, candidate.key),
        )
    }

    /**
     * Sonraki parça önerileri: en iyi eşleşme, güvenli (ton+BPM mükemmel, enerji yakın),
     * deneysel (iyi puanlı ama ton/enerji/tür olarak farklı). En az üç öneri (aday varsa).
     */
    fun suggest(current: TrackFeatures, candidates: List<TrackFeatures>, mode: AssistMode = AssistMode.NONE): List<Suggestion> {
        val scored = candidates.filter { it.id != current.id }.map { it to match(current, it, mode) }.sortedByDescending { it.second.total }
        if (scored.isEmpty()) return emptyList()
        val out = mutableListOf<Suggestion>()
        val used = mutableSetOf<String>()
        fun add(p: Pair<TrackFeatures, MatchScore>?, kind: SuggestionKind) {
            if (p == null || p.first.id in used) return
            used += p.first.id
            out += Suggestion(p.first, p.second, kind)
        }
        add(scored.first(), SuggestionKind.BEST)
        add(
            scored.firstOrNull { (t, s) ->
                t.id !in used && s.keyCompat == Compat.EXCELLENT && s.bpmCompat == Compat.EXCELLENT &&
                    (current.energy == null || t.energy == null || abs(t.energy - current.energy) <= 6)
            } ?: scored.firstOrNull { it.first.id !in used },
            SuggestionKind.SAFE,
        )
        add(
            scored.firstOrNull { (t, s) ->
                t.id !in used && (
                    s.keyCompat == Compat.POSSIBLE || s.genre < 0.5f ||
                        (current.energy != null && t.energy != null && abs(t.energy - current.energy) > 10)
                    )
            } ?: scored.firstOrNull { it.first.id !in used },
            SuggestionKind.WILDCARD,
        )
        return out
    }

    data class PlaylistFilter(
        val bpmMin: Float? = null,
        val bpmMax: Float? = null,
        /** Yalnızca bu Camelot kodları (boşsa hepsi) */
        val camelot: Set<String> = emptySet(),
        val energyMin: Int? = null,
        val energyMax: Int? = null,
        val genre: String? = null,
        val maxDurationMs: Long? = null,
        val energyRising: Boolean = true,
        val harmonic: Boolean = true,
    )

    /**
     * DJ'ye uygun liste: filtreler, sonra enerjisi düşükten başlayıp her adımda
     * hedef enerji (+5) ve harmonik uyuma göre en iyi sıradaki parçayı seçen açgözlü sıralama.
     * BPM'i bilinmeyen parçalar BPM filtresine takılır (bilinmiyorsa uyduğu varsayılmaz).
     */
    fun smartPlaylist(tracks: List<TrackFeatures>, f: PlaylistFilter): List<TrackFeatures> {
        val pool = tracks.filter { t ->
            (f.bpmMin == null || (t.bpm != null && t.bpm >= f.bpmMin)) &&
                (f.bpmMax == null || (t.bpm != null && t.bpm <= f.bpmMax)) &&
                (f.camelot.isEmpty() || t.key?.camelot in f.camelot) &&
                (f.energyMin == null || (t.energy != null && t.energy >= f.energyMin)) &&
                (f.energyMax == null || (t.energy != null && t.energy <= f.energyMax)) &&
                (f.genre.isNullOrBlank() || genreScore(f.genre, t.genre) == 1f) &&
                (f.maxDurationMs == null || t.durationMs in 1..f.maxDurationMs)
        }.toMutableList()
        if (pool.size <= 1 || (!f.energyRising && !f.harmonic)) return pool
        val out = mutableListOf<TrackFeatures>()
        var cur = if (f.energyRising) pool.minBy { it.energy ?: 50 } else pool.first()
        pool.remove(cur)
        out += cur
        while (pool.isNotEmpty()) {
            val base = cur
            val next = pool.maxBy { c ->
                val key = if (f.harmonic) keyScore(base.key, c.key) else NEUTRAL
                val energy = if (f.energyRising) energyScore(base.energy?.let { it + 5 }, c.energy) else NEUTRAL
                val drop = if (f.energyRising && base.energy != null && c.energy != null && c.energy < base.energy - 3) 0.3f else 0f
                0.45f * bpmScore(base.bpm, c.bpm) + 0.35f * key + 0.2f * energy - drop
            }
            pool.remove(next)
            out += next
            cur = next
        }
        return out
    }
}
