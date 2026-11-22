package com.melodi.audio.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RangeMathAndChunkMapTest {
    @Test fun parsesContentRange() {
        assertEquals(ContentRange(0, 0, 1000), RangeMath.parseContentRange("bytes 0-0/1000"))
        assertEquals(ContentRange(500, 999, null), RangeMath.parseContentRange("bytes 500-999/*"))
        assertEquals(ContentRange(5, 9, 10), RangeMath.parseContentRange("  Bytes 5-9/10 "))
        assertNull(RangeMath.parseContentRange(null))
        assertNull(RangeMath.parseContentRange("bytes */1000"))
        assertNull(RangeMath.parseContentRange("bytes 9-5/10"))
        assertNull(RangeMath.parseContentRange("bytes 0-10/10"))
        assertNull(RangeMath.parseContentRange("items 0-1/2"))
    }

    @Test fun chunkPlanIsClampedAndCoversFile() {
        assertEquals(RangeMath.MIN_CHUNK, RangeMath.chunkSize(3L * 1024 * 1024))
        assertEquals(RangeMath.MAX_CHUNK, RangeMath.chunkSize(10L * 1024 * 1024 * 1024))
        val total = 10_000_001L
        val size = RangeMath.chunkSize(total)
        val n = RangeMath.chunkCount(total, size)
        var covered = 0L
        for (i in 0 until n) {
            val r = RangeMath.chunkBounds(i, size, total)
            assertEquals(covered, r.first)
            covered = r.last + 1
        }
        assertEquals(total, covered)
    }

    @Test fun chunkMapClaimsSavesAndReloads() {
        val f = File(Files.createTempDirectory("map").toFile(), "m.map")
        val map = ChunkMap.fresh(total = 2500, chunkSize = 1000, fingerprint = "fp")
        assertEquals(3, map.count)
        val a = map.claimNext()!!
        val b = map.claimNext()!!
        map.markDone(a)
        map.release(b)
        assertEquals(1000, map.doneBytes())
        map.save(f)

        val loaded = ChunkMap.loadIfValid(f, 2500, "fp")
        assertNotNull(loaded)
        loaded!!
        assertTrue(loaded.isDone(a))
        assertFalse(loaded.isDone(b))
        assertEquals(2, loaded.remaining())
        assertEquals(1000, loaded.chunkSize)

        assertNull("parmak izi tutmuyorsa sıfırdan", ChunkMap.loadIfValid(f, 2500, "baska"))
        assertNull("boyut tutmuyorsa sıfırdan", ChunkMap.loadIfValid(f, 2600, "fp"))
    }

    @Test fun lastChunkIsShorter() {
        val map = ChunkMap.fresh(total = 2500, chunkSize = 1000, fingerprint = "fp")
        repeat(3) { map.markDone(map.claimNext()!!) }
        assertTrue(map.isComplete())
        assertEquals(2500, map.doneBytes())
        assertNull(map.claimNext())
    }

    @Test fun fingerprintIgnoresQueryButKeepsPath() {
        val a = Fingerprint.of(10, "\"e\"", null, "https://cdn.example.org/a/b.mp3?sig=1")
        val b = Fingerprint.of(10, "\"e\"", null, "https://cdn.example.org/a/b.mp3?sig=2")
        val c = Fingerprint.of(10, "\"e\"", null, "https://cdn.example.org/a/c.mp3?sig=1")
        assertEquals(a.key, b.key)
        assertTrue(a.key != c.key)
        assertTrue(a.sameContent(c))
    }
}
