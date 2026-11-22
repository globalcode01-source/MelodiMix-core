package com.melodi.audio.download

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.BitSet

/**
 * Parça haritası: hangi parçaların inip diske yazıldığı. Kesilen indirme bu dosyadan kaldığı yerden sürer.
 * İşçiler [claimNext] ile sıradaki boş parçayı alır; yarım kalan parça haritaya "bitti" yazılmaz, baştan iner.
 */
class ChunkMap private constructor(
    val total: Long,
    val chunkSize: Long,
    val fingerprint: String,
    private val done: BitSet,
) {
    val count: Int = RangeMath.chunkCount(total, chunkSize)
    private val inFlight = BitSet(count)

    @Synchronized
    fun claimNext(): Int? {
        for (i in 0 until count) {
            if (!done[i] && !inFlight[i]) {
                inFlight.set(i)
                return i
            }
        }
        return null
    }

    @Synchronized
    fun markDone(index: Int) {
        done.set(index)
        inFlight.clear(index)
    }

    /** Parça inemedi: başka bir işçi (ya da sonraki çalıştırma) yeniden alabilsin. */
    @Synchronized
    fun release(index: Int) = inFlight.clear(index)

    @Synchronized
    fun isDone(index: Int) = done[index]

    @Synchronized
    fun isComplete() = done.cardinality() == count

    @Synchronized
    fun remaining() = count - done.cardinality()

    @Synchronized
    fun doneBytes(): Long {
        var sum = 0L
        var i = done.nextSetBit(0)
        while (i in 0 until count) {
            val r = RangeMath.chunkBounds(i, chunkSize, total)
            sum += r.last - r.first + 1
            i = done.nextSetBit(i + 1)
        }
        return sum
    }

    @Synchronized
    fun toJson(): String = JSONObject()
        .put("v", 1)
        .put("mode", "parallel")
        .put("total", total)
        .put("chunk", chunkSize)
        .put("fp", fingerprint)
        .put("done", JSONArray().apply { done.toLongArray().forEach { put(it) } })
        .toString()

    /** Önce geçici dosyaya yazıp yerine taşır: yazarken kesilirse eski harita bozulmaz. */
    fun save(file: File) = writeAtomic(file, toJson())

    companion object {
        fun fresh(total: Long, chunkSize: Long, fingerprint: String) = ChunkMap(total, chunkSize, fingerprint, BitSet())

        /** Kayıtlı harita aynı dosyaya (boyut + parmak izi) aitse yükler; değilse null (sıfırdan başlanır). */
        fun loadIfValid(file: File, total: Long, fingerprint: String): ChunkMap? = runCatching {
            if (!file.exists()) return null
            val o = JSONObject(file.readText())
            if (o.optString("mode") != "parallel" || o.getLong("total") != total || o.getString("fp") != fingerprint) return null
            val chunk = o.getLong("chunk")
            if (chunk <= 0) return null
            val arr = o.getJSONArray("done")
            val bits = BitSet.valueOf(LongArray(arr.length()) { arr.getLong(it) })
            ChunkMap(total, chunk, fingerprint, bits)
        }.getOrNull()

        /** Tek akış modu: yalnızca parmak izi tutulur, ilerleme dosyanın uzunluğudur. */
        fun saveSingle(file: File, fingerprint: String) =
            writeAtomic(file, JSONObject().put("v", 1).put("mode", "single").put("fp", fingerprint).toString())

        fun singleMatches(file: File, fingerprint: String): Boolean = runCatching {
            val o = JSONObject(file.readText())
            o.optString("mode") == "single" && o.getString("fp") == fingerprint
        }.getOrDefault(false)

        private fun writeAtomic(file: File, text: String) {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
