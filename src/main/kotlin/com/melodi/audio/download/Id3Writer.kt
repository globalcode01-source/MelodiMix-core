package com.melodi.audio.download

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Bağımlılıksız küçük ID3v2.4 yazıcı (MP3). Yeniden kodlama yapmaz: dosyanın başındaki etiketi değiştirir,
 * ses verisine dokunmaz. Eski etiketin bizim yazmadığımız çerçeveleri (tür, BPM, ton, yıl…) korunur;
 * DJ analizi bunları okuyor ([com.melodi.audio.dj.Id3]).
 */
object Id3Writer {
    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        /** Lisans atfı: "Sanatçı — Lisans — sayfa" (CC-BY atıf ister) */
        val comment: String? = null,
        /** Parçanın kaynak sayfası (WOAS) */
        val sourceUrl: String? = null,
        val cover: ByteArray? = null,
        val coverMime: String? = null,
    )

    internal class Frame(val id: String, val flags: Int, val data: ByteArray)

    private class OldTag(val frames: List<Frame>, val audioStart: Long)

    private const val MAX_TAG = 16 * 1024 * 1024
    private const val COMMENT_LANG = "tur"

    /** v2.3'te olup v2.4'te karşılığı olan çerçeveler; karşılığı olmayanlar atılır. */
    private val V3_TO_V4 = mapOf("TYER" to "TDRC", "TORY" to "TDOR")
    private val V3_ONLY = setOf("TDAT", "TIME", "TRDA", "TSIZ", "RVAD", "EQUA", "IPLS")

    fun write(file: File, tags: Tags) {
        val old = readOldTag(file)
        val ours = buildList {
            tags.title?.takeIf { it.isNotBlank() }?.let { add(text("TIT2", it)) }
            tags.artist?.takeIf { it.isNotBlank() }?.let { add(text("TPE1", it)) }
            tags.album?.takeIf { it.isNotBlank() }?.let { add(text("TALB", it)) }
            tags.comment?.takeIf { it.isNotBlank() }?.let { add(comment(it)) }
            tags.sourceUrl?.takeIf { it.isNotBlank() }?.let { add(Frame("WOAS", 0, it.toByteArray(Charsets.ISO_8859_1))) }
            if (tags.cover != null && tags.cover.isNotEmpty()) add(picture(tags.cover, tags.coverMime ?: "image/jpeg"))
        }
        val replaced = ours.map { it.id }.toSet()
        val kept = old.frames.filter { f ->
            f.id !in replaced || (f.id == "COMM" && !isOurComment(f))
        }
        val tag = buildTag(ours + kept)

        val tmp = File(file.path + ".id3tmp")
        tmp.outputStream().buffered().use { out ->
            out.write(tag)
            file.inputStream().use { input ->
                var skip = old.audioStart
                while (skip > 0) {
                    val n = input.skip(skip)
                    if (n <= 0) break
                    skip -= n
                }
                input.copyTo(out)
            }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Etiketlenen dosya yerine taşınamadı" }
        }
    }

    internal fun buildTag(frames: List<Frame>): ByteArray {
        val body = ByteArrayOutputStream()
        for (f in frames) {
            body.write(f.id.toByteArray(Charsets.ISO_8859_1))
            body.write(syncsafe(f.data.size))
            body.write((f.flags shr 8) and 0xff)
            body.write(f.flags and 0xff)
            body.write(f.data)
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0))
        out.write(syncsafe(body.size()))
        body.writeTo(out)
        return out.toByteArray()
    }

    private fun text(id: String, value: String) = Frame(id, 0, byteArrayOf(3) + value.toByteArray(Charsets.UTF_8))

    private fun comment(value: String): Frame {
        val d = ByteArrayOutputStream()
        d.write(3)
        d.write(COMMENT_LANG.toByteArray(Charsets.ISO_8859_1))
        d.write(0) // boş kısa açıklama
        d.write(value.toByteArray(Charsets.UTF_8))
        return Frame("COMM", 0, d.toByteArray())
    }

    /** Bizim yazdığımız biçimdeki yorum (aynı dil, boş açıklama) yenisiyle değişir; diğer yorumlar kalır. */
    private fun isOurComment(f: Frame): Boolean =
        f.data.size >= 5 && String(f.data, 1, 3, Charsets.ISO_8859_1) == COMMENT_LANG && f.data[4] == 0.toByte()

    private fun picture(bytes: ByteArray, mime: String): Frame {
        val d = ByteArrayOutputStream()
        d.write(0)
        d.write(mime.toByteArray(Charsets.ISO_8859_1))
        d.write(0)
        d.write(3) // ön kapak
        d.write(0) // boş açıklama
        d.write(bytes)
        return Frame("APIC", 0, d.toByteArray())
    }

    private fun readOldTag(file: File): OldTag = RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < 10) return OldTag(emptyList(), 0)
        val h = ByteArray(10)
        raf.readFully(h)
        if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return OldTag(emptyList(), 0)
        val version = h[3].toInt()
        val flags = h[5].toInt() and 0xff
        val size = syncsafe(h, 6)
        val footer = if (version == 4 && flags and 0x10 != 0) 10 else 0
        val audioStart = 10L + size + footer
        // Bozuk başlık: etikete dokunmadan önüne yenisini koy
        if (size < 0 || audioStart > raf.length()) return OldTag(emptyList(), 0)
        // Okuyamadığımız eski etiket (v2.2, genişletilmiş başlık, v2.4'te etiket düzeyinde uyumsuzlaştırma) atılır,
        // yerine yenisi yazılır. v2.3'te uyumsuzlaştırma bütün gövdeye uygulanır (Internet Archive böyle yazıyor): çözülür.
        val unsynced = flags and 0x80 != 0
        if (version !in 3..4 || flags and 0x40 != 0 || (unsynced && version == 4) || size > MAX_TAG) return OldTag(emptyList(), audioStart)
        val raw = ByteArray(size)
        raf.readFully(raw)
        val body = if (unsynced) deunsync(raw) else raw
        OldTag(parseFrames(body, version), audioStart)
    }

    /** ID3 uyumsuzlaştırmasını geri alır: FF 00 → FF. Yeni etiket uyumsuzlaştırmasız yazılır. */
    private fun deunsync(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    internal fun parseFrames(d: ByteArray, version: Int): List<Frame> {
        val out = mutableListOf<Frame>()
        var pos = 0
        while (pos + 10 <= d.size) {
            val idBytes = d.copyOfRange(pos, pos + 4)
            if (idBytes.any { b -> !(b in 'A'.code.toByte()..'Z'.code.toByte() || b in '0'.code.toByte()..'9'.code.toByte()) }) break
            var id = String(idBytes, Charsets.ISO_8859_1)
            val size = if (version == 4) syncsafe(d, pos + 4) else int32(d, pos + 4)
            val flags = ((d[pos + 8].toInt() and 0xff) shl 8) or (d[pos + 9].toInt() and 0xff)
            val start = pos + 10
            if (size <= 0 || start + size > d.size) break
            val data = d.copyOfRange(start, start + size)
            pos = start + size
            if (version == 3) {
                // v2.3 sıkıştırma/şifreleme/gruplama: taşınamaz, atla
                if (flags and 0x00E0 != 0) continue
                if (id in V3_ONLY) continue
                id = V3_TO_V4[id] ?: id
                out += Frame(id, 0, data)
            } else {
                out += Frame(id, flags, data)
            }
        }
        return out
    }

    private fun syncsafe(n: Int): ByteArray {
        require(n in 0 until (1 shl 28)) { "ID3 boyutu çok büyük: $n" }
        return byteArrayOf(
            ((n shr 21) and 0x7f).toByte(),
            ((n shr 14) and 0x7f).toByte(),
            ((n shr 7) and 0x7f).toByte(),
            (n and 0x7f).toByte(),
        )
    }

    private fun syncsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7f) shl 21) or ((b[o + 1].toInt() and 0x7f) shl 14) or ((b[o + 2].toInt() and 0x7f) shl 7) or (b[o + 3].toInt() and 0x7f)

    private fun int32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
}
