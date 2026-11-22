package com.melodi.audio.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarmonyTest {
    private fun key(s: String) = MusicalKey.parse(s)!!

    @Test
    fun camelotMapping() {
        assertEquals("8A", key("Am").camelot)
        assertEquals("8B", key("C").camelot)
        assertEquals("5A", key("Cm").camelot)
        assertEquals("12A", key("C#m").camelot)
        assertEquals("11A", key("F#m").camelot)
        assertEquals("1B", key("B").camelot)
        assertEquals("12B", key("E").camelot)
        assertEquals("4A", key("Fm").camelot)
        assertEquals("Am", key("8A").name)
        assertEquals("D#m", key("Ebm").name)
        assertEquals("C#m", key("C# minor").name)
        assertNull(MusicalKey.parse("hello"))
        assertNull(MusicalKey.parse("13A"))
    }

    @Test
    fun harmonicRules() {
        assertEquals(Compat.EXCELLENT, Harmony.keyCompat(key("8A"), key("8A")))
        assertEquals(Compat.EXCELLENT, Harmony.keyCompat(key("8A"), key("7A")))
        assertEquals(Compat.EXCELLENT, Harmony.keyCompat(key("8A"), key("9A")))
        assertEquals(Compat.EXCELLENT, Harmony.keyCompat(key("8A"), key("8B")))
        assertEquals(Compat.EXCELLENT, Harmony.keyCompat(key("12A"), key("1A"))) // çark kapanır
        assertEquals(Compat.DIFFICULT, Harmony.keyCompat(key("8A"), key("2A")))
        assertEquals(Compat.UNKNOWN, Harmony.keyCompat(null, key("2A")))
    }

    @Test
    fun bpmRulesIncludingHalfAndDouble() {
        assertEquals(Compat.EXCELLENT, Harmony.bpmCompat(124f, 125f))
        assertEquals(Compat.EXCELLENT, Harmony.bpmCompat(128f, 64f))
        assertEquals(Compat.EXCELLENT, Harmony.bpmCompat(70f, 140f))
        assertEquals(Compat.POSSIBLE, Harmony.bpmCompat(120f, 126f))
        assertEquals(Compat.DIFFICULT, Harmony.bpmCompat(120f, 100f))
        assertEquals(0.8f, Harmony.bpmScore(120f, 125f), 0.001f)
        assertEquals(0.5f, Harmony.bpmScore(120f, 128f), 0.001f)
    }

    @Test
    fun matchScoreWeightsAndRanking() {
        val cur = TrackFeatures("cur", 124f, key("8A"), 72, "house")
        val perfect = TrackFeatures("a", 124f, key("8A"), 75, "house", introMs = 20_000)
        val ok = TrackFeatures("b", 126f, key("9A"), 79, "house")
        val far = TrackFeatures("c", 98f, key("3B"), 30, "rock")
        val s = Harmony.match(cur, perfect)
        assertTrue(s.total >= 95)
        assertTrue(Harmony.match(cur, ok).total in 80..95)
        assertTrue(Harmony.match(cur, far).total < 40)
        val sug = Harmony.suggest(cur, listOf(far, ok, perfect))
        assertEquals("a", sug.first().track.id)
        assertEquals(SuggestionKind.BEST, sug.first().kind)
        assertEquals(3, sug.size)
    }

    @Test
    fun assistModesChangeRanking() {
        val cur = TrackFeatures("cur", 124f, key("8A"), 60)
        val calm = TrackFeatures("calm", 124f, key("8A"), 45)
        val hot = TrackFeatures("hot", 124f, key("8A"), 75)
        assertEquals("hot", Harmony.suggest(cur, listOf(calm, hot), AssistMode.ENERGY_UP).first().track.id)
        assertEquals("calm", Harmony.suggest(cur, listOf(calm, hot), AssistMode.ENERGY_DOWN).first().track.id)
    }

    @Test
    fun unknownAnalysisIsNeutralNotFake() {
        val cur = TrackFeatures("cur")
        val s = Harmony.match(cur, TrackFeatures("x"))
        assertEquals(50, s.total)
        assertEquals(Compat.UNKNOWN, s.bpmCompat)
    }

    @Test
    fun smartPlaylistFiltersAndRisesInEnergy() {
        val tracks = listOf(
            TrackFeatures("e80", 125f, key("9A"), 80),
            TrackFeatures("e50", 122f, key("8A"), 50),
            TrackFeatures("e65", 124f, key("8A"), 65),
            TrackFeatures("slow", 90f, key("8A"), 40),
            TrackFeatures("nobpm", null, key("8A"), 55),
        )
        val list = Harmony.smartPlaylist(tracks, Harmony.PlaylistFilter(bpmMin = 120f, bpmMax = 126f))
        assertEquals(listOf("e50", "e65", "e80"), list.map { it.id })
    }
}
