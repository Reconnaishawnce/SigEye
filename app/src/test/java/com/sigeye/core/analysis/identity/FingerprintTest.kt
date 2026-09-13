package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintTest {

    private fun shape(
        company: Int? = 0x004C,
        services: List<String> = listOf("180f", "180a"),
        name: String? = "Phone",
        length: Int = 25,
        prefix: String? = "0215",
    ) = AdvertShape(
        companyId = company,
        serviceUuids = services,
        name = name,
        manufacturerLength = length,
        manufacturerPrefix = prefix,
    )

    private fun identity(
        address: String,
        shape: AdvertShape = shape(),
        isRandom: Boolean = true,
        firstSeenMs: Long = 0L,
        lastSeenMs: Long = 60_000L,
        intervalMs: Long = 100L,
        rssi: Double = -55.0,
    ) = Identity(
        address = address,
        shape = shape,
        isRandom = isRandom,
        firstSeenMs = firstSeenMs,
        lastSeenMs = lastSeenMs,
        packets = 300,
        medianGapMs = intervalMs,
        recentRssi = rssi,
        bestRssi = rssi.toInt(),
    )

    // ------------------------------------------------------------------ shapes

    @Test
    fun `the same structure produces the same key whatever order it arrives in`() {
        val a = shape(services = listOf("180f", "180a"))
        val b = shape(services = listOf("180a", "180f"))
        assertEquals(a.key, b.key)
    }

    @Test
    fun `a different structure produces a different key`() {
        assertTrue(shape().key != shape(company = 0x0006).key)
        assertTrue(shape().key != shape(name = "Other").key)
        assertTrue(shape().key != shape(length = 31).key)
    }

    @Test
    fun `an advertisement carrying almost nothing is refused as a basis for matching`() {
        // Matching on a bare company ID would link half a street together.
        val bare = AdvertShape(companyId = 0x004C)
        assertTrue(bare.tooPlainToMatchOn)
        assertTrue(!shape().tooPlainToMatchOn)
    }

    // -------------------------------------------------------------- the linking

    @Test
    fun `a textbook rotation is rated strongly`() {
        val old = identity("AA", lastSeenMs = 60_000L)
        val new = identity("BB", firstSeenMs = 62_000L, lastSeenMs = 90_000L)
        val score = Fingerprint.score(old, new)

        assertEquals(LinkConfidence.STRONG, score.confidence)
        assertTrue(score.supporting.any { it.text.contains("same structure") })
        assertTrue(score.supporting.any { it.text.contains("firmware constant") })
    }

    @Test
    fun `a fixed address is never linked, because fixed addresses do not rotate`() {
        val old = identity("AA", lastSeenMs = 60_000L)
        val new = identity("BB", isRandom = false, firstSeenMs = 62_000L)
        val score = Fingerprint.score(old, new)

        assertEquals(LinkConfidence.NONE, score.confidence)
        assertTrue(score.against.any { it.text.contains("fixed address does not rotate") })
    }

    @Test
    fun `a different structure and a different interval is not a link`() {
        val old = identity("AA", lastSeenMs = 60_000L)
        val new = identity(
            "BB",
            shape = shape(company = 0x0006, name = "Something else"),
            firstSeenMs = 62_000L,
            intervalMs = 1_000L,
        )
        assertEquals(LinkConfidence.NONE, Fingerprint.score(old, new).confidence)
    }

    @Test
    fun `matching structure alone is not enough to be certain`() {
        // Right shape, wrong everything else: appeared an hour later, from far away, at a
        // different rate. Worth mentioning, not worth asserting.
        val old = identity("AA", lastSeenMs = 60_000L)
        val new = identity(
            "BB",
            firstSeenMs = 3_600_000L,
            intervalMs = 1_000L,
            rssi = -95.0,
        )
        val score = Fingerprint.score(old, new)
        assertTrue(score.confidence != LinkConfidence.STRONG)
    }

    @Test
    fun `a big jump in signal counts against a link`() {
        val old = identity("AA", lastSeenMs = 60_000L, rssi = -45.0)
        val new = identity("BB", firstSeenMs = 62_000L, rssi = -92.0)
        val score = Fingerprint.score(old, new)
        assertTrue(score.against.any { it.text.contains("did not move") })
        assertTrue(score.confidence != LinkConfidence.STRONG)
    }

    @Test
    fun `a plain advertisement cannot reach the top rating on structure`() {
        val bare = AdvertShape(companyId = 0x004C)
        val old = identity("AA", shape = bare, lastSeenMs = 60_000L)
        val new = identity("BB", shape = bare, firstSeenMs = 62_000L)
        val score = Fingerprint.score(old, new)
        assertTrue(score.against.any { it.text.contains("too plain") })
        assertTrue(score.confidence != LinkConfidence.STRONG)
    }

    @Test
    fun `every rating carries its reasoning, for and against`() {
        val score = Fingerprint.score(identity("AA"), identity("BB", firstSeenMs = 62_000L))
        assertEquals(5, score.evidence.size)
        assertTrue(score.evidence.all { it.text.isNotBlank() })
    }

    // ------------------------------------------------------------- the interval

    @Test
    fun `the base interval comes from the gaps where nothing was missed`() {
        // A scanner misses packets, so most gaps are two or three intervals. The median
        // would land on whichever multiple dominated; the true interval is at the bottom.
        val gaps = listOf(100L, 100L, 200L, 300L, 200L, 100L, 400L, 200L, 300L, 200L)
        assertEquals(100L, Fingerprint.baseIntervalMs(gaps))
    }

    @Test
    fun `too few gaps gives no estimate rather than a bad one`() {
        assertEquals(0L, Fingerprint.baseIntervalMs(listOf(100L, 100L)))
        assertEquals(0L, Fingerprint.baseIntervalMs(emptyList()))
    }

    @Test
    fun `intervals within the slop of each other agree`() {
        assertTrue(Fingerprint.intervalsAgree(100L, 110L))
        assertTrue(Fingerprint.intervalsAgree(1_022L, 1_000L))
        assertTrue(!Fingerprint.intervalsAgree(100L, 1_000L))
        assertTrue(!Fingerprint.intervalsAgree(0L, 100L))
    }
}
