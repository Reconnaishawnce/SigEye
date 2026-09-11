package com.sigeye.core.cell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellTrackerTest {

    private fun sample(
        cellId: Long? = 1000L,
        areaCode: Int? = 10,
        pci: Int? = 50,
        dbm: Int = -85,
        atMs: Long = 0L,
        technology: String = "LTE",
    ) = CellSample(
        technology = technology,
        operator = "23410",
        cellId = cellId,
        areaCode = areaCode,
        pci = pci,
        channel = 1815,
        dbm = dbm,
        level = 3,
        atMs = atMs,
    )

    @Test
    fun `the first sample is not a handover`() {
        val t = CellTracker()
        assertNull(t.observe(sample()))
        assertEquals(0, t.stats(0L).handovers.size)
        assertEquals(1, t.stats(0L).distinctCells)
    }

    @Test
    fun `repeated readings of the same cell are not handovers`() {
        val t = CellTracker()
        t.observe(sample(atMs = 0L))
        assertNull(t.observe(sample(dbm = -90, atMs = 3_000L)))
        assertNull(t.observe(sample(dbm = -70, atMs = 6_000L)))
        assertEquals(0, t.stats(6_000L).handovers.size)
    }

    @Test
    fun `a different cell id is a handover`() {
        val t = CellTracker()
        t.observe(sample(cellId = 1000L, atMs = 0L))
        val handover = t.observe(sample(cellId = 2000L, atMs = 30_000L))
        assertNotNull(handover)
        assertEquals(30_000L, handover!!.heldPreviousMs)
        assertFalse(handover.areaChanged)
        assertEquals(2, t.stats(30_000L).distinctCells)
    }

    @Test
    fun `a changed area code is flagged as the larger move it is`() {
        val t = CellTracker()
        t.observe(sample(cellId = 1000L, areaCode = 10, atMs = 0L))
        val handover = t.observe(sample(cellId = 2000L, areaCode = 11, atMs = 10_000L))
        assertNotNull(handover)
        assertTrue(handover!!.areaChanged)
    }

    @Test
    fun `signal strength alone never counts as a handover`() {
        val t = CellTracker()
        t.observe(sample(dbm = -60, atMs = 0L))
        assertNull(t.observe(sample(dbm = -115, atMs = 5_000L)))
        assertEquals(0, t.stats(5_000L).handovers.size)
    }

    @Test
    fun `a reading with no identity does not invent a handover`() {
        val t = CellTracker()
        t.observe(sample(cellId = 1000L, atMs = 0L))
        // Modems return partial identities regularly. That is not movement.
        assertNull(t.observe(sample(cellId = null, pci = null, atMs = 5_000L)))
        assertEquals(0, t.stats(5_000L).handovers.size)
    }

    @Test
    fun `recovering identity after a blank reading does not double count`() {
        val t = CellTracker()
        t.observe(sample(cellId = 1000L, atMs = 0L))
        t.observe(sample(cellId = null, pci = null, atMs = 5_000L))
        val handover = t.observe(sample(cellId = 2000L, atMs = 10_000L))
        // One move, from the blank reading to the new cell - not two.
        assertNotNull(handover)
        assertEquals(1, t.stats(10_000L).handovers.size)
    }

    @Test
    fun `changing technology counts as a handover`() {
        val t = CellTracker()
        t.observe(sample(technology = "LTE", atMs = 0L))
        val handover = t.observe(sample(technology = "NR", atMs = 20_000L))
        assertNotNull(handover)
        assertEquals("NR", handover!!.at.technology)
    }

    @Test
    fun `handovers are reported newest first`() {
        val t = CellTracker()
        t.observe(sample(cellId = 1L, atMs = 0L))
        t.observe(sample(cellId = 2L, atMs = 10_000L))
        t.observe(sample(cellId = 3L, atMs = 20_000L))
        val list = t.stats(20_000L).handovers
        assertEquals(2, list.size)
        assertEquals(3L, list.first().at.cellId)
    }

    @Test
    fun `history is capped`() {
        val t = CellTracker(maxHandovers = 5)
        repeat(20) { t.observe(sample(cellId = it.toLong(), atMs = it * 1_000L)) }
        assertEquals(5, t.stats(20_000L).handovers.size)
    }

    @Test
    fun `rate is withheld until there is enough observation to mean anything`() {
        val t = CellTracker()
        t.observe(sample(cellId = 1L, atMs = 0L))
        t.observe(sample(cellId = 2L, atMs = 10_000L))
        assertNull("10 seconds is not a rate", t.stats(10_000L).handoversPerHour)

        // One handover in six minutes is ten per hour.
        val slow = CellTracker()
        slow.observe(sample(cellId = 1L, atMs = 0L))
        slow.observe(sample(cellId = 2L, atMs = 360_000L))
        assertEquals(10.0, slow.stats(360_000L).handoversPerHour!!, 0.001)
    }

    @Test
    fun `distinct cells counts unique identities, not visits`() {
        val t = CellTracker()
        t.observe(sample(cellId = 1L, atMs = 0L))
        t.observe(sample(cellId = 2L, atMs = 10_000L))
        t.observe(sample(cellId = 1L, atMs = 20_000L))
        assertEquals(2, t.stats(20_000L).distinctCells)
        assertEquals(2, t.stats(20_000L).handovers.size)
    }
}
