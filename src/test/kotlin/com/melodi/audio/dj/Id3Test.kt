package com.melodi.audio.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class Id3Test {
    private fun frame(id: String, text: String, enc: Int = 3): ByteArray {
        val body = byteArrayOf(enc.toByte()) + text.toByteArray(if (enc == 3) Charsets.UTF_8 else Charsets.ISO_8859_1)
        val o = ByteArrayOutputStream()
        o.write(id.toByteArray())
        val n = body.size
        o.write(byteArrayOf((n shr 21 and 0x7f).toByte(), (n shr 14 and 0x7f).toByte(), (n shr 7 and 0x7f).toByte(), (n and 0x7f).toByte()))
        o.write(byteArrayOf(0, 0))
        o.write(body)
        return o.toByteArray()
    }

    private fun tag(vararg frames: ByteArray, version: Int = 4): ByteArray {
        val body = frames.fold(ByteArray(0)) { a, b -> a + b } + ByteArray(32)
        val n = body.size
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), version.toByte(), 0, 0,
            (n shr 21 and 0x7f).toByte(), (n shr 14 and 0x7f).toByte(), (n shr 7 and 0x7f).toByte(), (n and 0x7f).toByte()) + body + ByteArray(100)
    }

    @Test
    fun readsDjFrames() {
        val t = Id3.read(tag(frame("TBPM", "124"), frame("TKEY", "F#m"), frame("TCON", "(35)"), frame("TIT2", "Gece Şarkısı")).inputStream())
        assertEquals(124f, t.bpm!!, 0f)
        assertEquals("11A", t.key!!.camelot)
        assertEquals("House", t.genre)
        assertEquals("Gece Şarkısı", t.title)
    }

    @Test
    fun ignoresMissingOrBadTags() {
        assertNull(Id3.read(ByteArray(64).inputStream()).bpm)
        assertNull(Id3.read(tag(frame("TBPM", "abc")).inputStream()).bpm)
        assertNull(Id3.read(tag(frame("TKEY", "o")).inputStream()).key)
    }
}
