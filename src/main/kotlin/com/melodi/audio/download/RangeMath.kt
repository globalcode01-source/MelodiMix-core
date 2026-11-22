package com.melodi.audio.download

import java.net.URI

/** "bytes a-b/toplam" başlığı; toplam bilinmiyorsa ("*") null. */
data class ContentRange(val start: Long, val end: Long, val total: Long?)

/**
 * Sunucunun verdiği dosyanın kimliği. Kaldığı yerden devam etmek için içerik aynı kalmalı:
 * boyut + ETag + Last-Modified aynıysa içerik aynı sayılır; [key] ayrıca adresin host/path kısmını içerir
 * (imzalı adreslerde query değişir, o yüzden dahil değil).
 */
data class Fingerprint(val total: Long?, val etag: String?, val lastModified: String?, val urlKey: String) {
    val key: String get() = listOf(total?.toString().orEmpty(), etag.orEmpty(), lastModified.orEmpty(), urlKey).joinToString("|")

    /** Aynı çalıştırmada adres yenilendiğinde: host değişebilir, içerik değişmemeli. */
    fun sameContent(other: Fingerprint) = total == other.total && etag == other.etag && lastModified == other.lastModified

    companion object {
        fun of(total: Long?, etag: String?, lastModified: String?, url: String): Fingerprint {
            val u = runCatching { URI(url) }.getOrNull()
            return Fingerprint(total, etag?.trim(), lastModified?.trim(), u?.host.orEmpty() + u?.rawPath.orEmpty())
        }
    }
}

object RangeMath {
    const val MIN_CHUNK = 512L * 1024
    const val MAX_CHUNK = 4L * 1024 * 1024
    const val MAX_WORKERS = 8

    /** Bu boyutun altındaki dosyalar tek akışla iner (paralellik kazandırmaz). */
    const val PARALLEL_THRESHOLD = 2L * 1024 * 1024

    private val CONTENT_RANGE = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)

    fun parseContentRange(header: String?): ContentRange? {
        val (a, b, t) = CONTENT_RANGE.find(header ?: return null)?.destructured ?: return null
        val start = a.toLongOrNull() ?: return null
        val end = b.toLongOrNull() ?: return null
        if (end < start) return null
        val total = if (t == "*") null else t.toLongOrNull() ?: return null
        if (total != null && end >= total) return null
        return ContentRange(start, end, total)
    }

    /** Parça boyutu: toplam / (işçi × 4), 512 KB–4 MB arasına sıkıştırılır. */
    fun chunkSize(total: Long, workers: Int = MAX_WORKERS): Long =
        (total / (workers * 4L)).coerceIn(MIN_CHUNK, MAX_CHUNK)

    fun chunkCount(total: Long, chunkSize: Long): Int = ((total + chunkSize - 1) / chunkSize).toInt()

    /** [index]. parçanın bayt aralığı (son bayt dahil). */
    fun chunkBounds(index: Int, chunkSize: Long, total: Long): LongRange {
        val start = index * chunkSize
        return start until minOf(start + chunkSize, total)
    }
}
