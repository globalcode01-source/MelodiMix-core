package com.melodi.audio.dj

import java.io.InputStream

/** Dosyanın kendi etiketinden gelen DJ bilgileri (ID3v2: TBPM, TKEY, TCON, TIT2, TPE1). */
data class TagInfo(val bpm: Float? = null, val key: MusicalKey? = null, val genre: String? = null, val title: String? = null, val artist: String? = null)

/**
 * Küçük ID3v2.2/2.3/2.4 okuyucu: yalnızca metin çerçeveleri, yalnızca etiket başlığı kadar okunur
 * (ses verisi belleğe alınmaz). Etiket yoksa ya da bozuksa boş sonuç döner.
 */
object Id3 {
    private const val MAX_TAG = 2 * 1024 * 1024

    fun read(input: InputStream): TagInfo {
        val header = ByteArray(10)
        if (readFully(input, header) < 10) return TagInfo()
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return TagInfo()
        val version = header[3].toInt()
        val flags = header[5].toInt()
        val size = syncsafe(header, 6)
        if (version !in 2..4 || size <= 0 || size > MAX_TAG) return TagInfo()
        val tag = ByteArray(size)
        val got = readFully(input, tag)
        val data = if (flags and 0x80 != 0 && version < 4) unsync(tag, got) else tag.copyOf(got)
        return parseFrames(data, version, flags)
    }

    internal fun parseFrames(d: ByteArray, version: Int, flags: Int): TagInfo {
        var pos = 0
        if (flags and 0x40 != 0 && version >= 3) {
            // Genişletilmiş başlığı atla
            val ext = if (version == 4) syncsafe(d, 0) else int32(d, 0) + 4
            pos += ext.coerceIn(0, d.size)
        }
        val idLen = if (version == 2) 3 else 4
        val headLen = if (version == 2) 6 else 10
        val texts = mutableMapOf<String, String>()
        while (pos + headLen <= d.size) {
            val id = String(d, pos, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = when (version) {
                2 -> ((d[pos + 3].toInt() and 0xff) shl 16) or ((d[pos + 4].toInt() and 0xff) shl 8) or (d[pos + 5].toInt() and 0xff)
                3 -> int32(d, pos + 4)
                else -> syncsafe(d, pos + 4)
            }
            val start = pos + headLen
            if (size <= 0 || start + size > d.size) break
            if (id.startsWith("T")) decodeText(d, start, size)?.let { texts[id] = it }
            pos = start + size
        }
        fun t(v3: String, v2: String) = texts[v3] ?: texts[v2]
        return TagInfo(
            bpm = t("TBPM", "TBP")?.replace(',', '.')?.toFloatOrNull()?.takeIf { it in 40f..250f },
            key = MusicalKey.parse(t("TKEY", "TKE")),
            genre = t("TCON", "TCO")?.let(::cleanGenre),
            title = t("TIT2", "TT2"),
            artist = t("TPE1", "TP1"),
        )
    }

    private fun decodeText(d: ByteArray, start: Int, size: Int): String? {
        if (size < 2) return null
        val enc = d[start].toInt()
        val body = d.copyOfRange(start + 1, start + size)
        val cs = when (enc) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(body, cs).split('\u0000').firstOrNull { it.isNotBlank() }?.trim()
    }

    /** "(17)Rock" / "(17)" / "Rock" → "Rock" (ID3v1 tür numaralarının yaygın olanları). */
    private fun cleanGenre(g: String): String? {
        val m = Regex("""^\((\d+)\)(.*)$""").matchEntire(g)
        val s = if (m != null) m.groupValues[2].ifBlank { V1_GENRES[m.groupValues[1].toInt()] ?: "" } else g
        return s.trim().ifBlank { null }
    }

    private val V1_GENRES = mapOf(
        0 to "Blues", 1 to "Classic Rock", 2 to "Country", 3 to "Dance", 4 to "Disco", 5 to "Funk", 6 to "Grunge", 7 to "Hip-Hop",
        8 to "Jazz", 9 to "Metal", 10 to "New Age", 12 to "Other", 13 to "Pop", 14 to "R&B", 15 to "Rap", 16 to "Reggae", 17 to "Rock",
        18 to "Techno", 20 to "Alternative", 24 to "Soundtrack", 26 to "Ambient", 31 to "Trance", 32 to "Classical", 35 to "House",
        52 to "Electronic",
    )

    private fun syncsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7f) shl 21) or ((b[o + 1].toInt() and 0x7f) shl 14) or ((b[o + 2].toInt() and 0x7f) shl 7) or (b[o + 3].toInt() and 0x7f)

    private fun int32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun unsync(b: ByteArray, n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(n)
        var i = 0
        while (i < n) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < n && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }
}
