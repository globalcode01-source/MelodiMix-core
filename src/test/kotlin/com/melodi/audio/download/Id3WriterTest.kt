package com.melodi.audio.download

import com.melodi.audio.dj.Id3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class Id3WriterTest {
    private val audio = ByteArray(5000) { (it * 7).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte() }

    private fun tmp(bytes: ByteArray) = File(Files.createTempDirectory("id3").toFile(), "a.mp3").apply { writeBytes(bytes) }

    /** Etiket boyutu syncsafe; başlıktaki boyut + 10 = ses verisinin başladığı yer. */
    private fun audioOf(f: File): ByteArray {
        val b = f.readBytes()
        val size = ((b[6].toInt() and 0x7f) shl 21) or ((b[7].toInt() and 0x7f) shl 14) or ((b[8].toInt() and 0x7f) shl 7) or (b[9].toInt() and 0x7f)
        return b.copyOfRange(10 + size, b.size)
    }

    @Test fun writesTagReadableByAppParser() {
        val f = tmp(audio)
        Id3Writer.write(f, Id3Writer.Tags(title = "Gönül Dağı", artist = "Şebnem & Çağrı", comment = "Şebnem — CC BY 4.0 — https://x/y", sourceUrl = "https://x/y", cover = byteArrayOf(1, 2, 3)))
        val info = f.inputStream().use { Id3.read(it) }
        assertEquals("Gönül Dağı", info.title)
        assertEquals("Şebnem & Çağrı", info.artist)
        assertEquals(4, f.readBytes()[3].toInt())
        assertArrayEquals("ses verisi değişmemeli", audio, audioOf(f))
    }

    @Test fun keepsForeignFramesFromOldV23Tag() {
        // v2.3 etiket: TIT2 (eski başlık), TCON (tür), TBPM, TYER
        val frames = ByteArrayOutputStream()
        fun v3(id: String, text: String) {
            val d = byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
            frames.write(id.toByteArray())
            frames.write(byteArrayOf(0, 0, (d.size shr 8).toByte(), d.size.toByte(), 0, 0))
            frames.write(d)
        }
        v3("TIT2", "Eski")
        v3("TCON", "House")
        v3("TBPM", "124")
        v3("TYER", "2021")
        val body = frames.toByteArray() + ByteArray(64) // dolgu
        val s = body.size
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((s shr 21) and 0x7f).toByte(), ((s shr 14) and 0x7f).toByte(), ((s shr 7) and 0x7f).toByte(), (s and 0x7f).toByte())
        val f = tmp(header + body + audio)

        Id3Writer.write(f, Id3Writer.Tags(title = "Yeni", artist = "Sanatçı"))
        val info = f.inputStream().use { Id3.read(it) }
        assertEquals("Yeni", info.title)
        assertEquals("Sanatçı", info.artist)
        assertEquals("House", info.genre)
        assertEquals(124f, info.bpm)
        assertArrayEquals(audio, audioOf(f))
        val text = String(f.readBytes(), Charsets.ISO_8859_1)
        assertTrue("TYER → TDRC", text.contains("TDRC") && !text.contains("TYER"))
    }

    /** Internet Archive'ın yazdığı gibi: v2.3, uyumsuzlaştırma bayrağı açık, UTF-16 yorum (FF FE → FF 00 FE). */
    @Test fun keepsFramesFromUnsynchronisedV23Tag() {
        fun frame(id: String, data: ByteArray) = id.toByteArray() + byteArrayOf(0, 0, (data.size shr 8).toByte(), data.size.toByte(), 0, 0) + data
        val comm = byteArrayOf(1) + "XXX".toByteArray() + byteArrayOf(0, 0) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "archive.org".toByteArray(Charsets.UTF_16LE)
        val genre = byteArrayOf(0) + "Jazz".toByteArray()
        val plain = frame("COMM", comm) + frame("TCON", genre)
        // Uyumsuzlaştır: her FF'den sonra 00
        val body = java.io.ByteArrayOutputStream().apply {
            plain.forEach { b -> write(b.toInt()); if (b == 0xFF.toByte()) write(0) }
            write(ByteArray(32))
        }.toByteArray()
        val s = body.size
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0x80.toByte(),
            ((s shr 21) and 0x7f).toByte(), ((s shr 14) and 0x7f).toByte(), ((s shr 7) and 0x7f).toByte(), (s and 0x7f).toByte())
        val f = tmp(header + body + audio)

        Id3Writer.write(f, Id3Writer.Tags(title = "Başlık", comment = "Sanatçı — Kamu malı"))
        val info = f.inputStream().use { Id3.read(it) }
        assertEquals("Başlık", info.title)
        assertEquals("Jazz", info.genre)
        val bytes = f.readBytes()
        assertEquals("yeni etiket uyumsuzlaştırmasız", 0, bytes[5].toInt())
        val text = String(bytes, Charsets.ISO_8859_1)
        assertEquals("Archive'ın yorumu + bizim atıf", 2, Regex("COMM").findAll(text).count())
        assertTrue(String(bytes, Charsets.UTF_16LE).contains("archive.org") || text.contains("a\u0000r\u0000c\u0000h"))
        assertArrayEquals(audio, audioOf(f))
    }

    @Test fun retaggingReplacesOwnCommentInsteadOfDuplicating() {
        val f = tmp(audio)
        Id3Writer.write(f, Id3Writer.Tags(comment = "bir"))
        Id3Writer.write(f, Id3Writer.Tags(comment = "iki"))
        val text = String(f.readBytes(), Charsets.UTF_8)
        assertEquals(1, Regex("COMM").findAll(text).count())
        assertTrue(text.contains("iki"))
        assertArrayEquals(audio, audioOf(f))
    }
}
