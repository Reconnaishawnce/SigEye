package com.sigeye.experiments.bench

import com.sigeye.core.Experiments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The join between the bench's measurements and the registry rows behind them.
 *
 * Seven experiments became seven modes of one screen, and each still draws its header, its
 * walkthrough and its limits from its own registry row. That join is why the consolidation
 * cost nothing, and it is a string lookup, so it is exactly the kind of thing that breaks
 * without anybody noticing.
 */
class BenchModesTest {

    @Test
    fun `every measurement names a registry entry that exists`() {
        Bench.MEASUREMENTS.forEach { mode ->
            assertNotNull(
                "${mode.name} points at \"${mode.experimentId}\", which is not in the registry",
                Experiments.byId(mode.experimentId!!),
            )
        }
    }

    @Test
    fun `every measurement is a member of the bench it is drawn inside`() {
        // A mode whose registry row still counts as standalone would appear both as its own
        // card on the home screen and as a chip in here, which is the opposite of the point.
        Bench.MEASUREMENTS.forEach { mode ->
            assertEquals(
                "${mode.name} is drawn inside the bench but is not marked as part of it",
                Experiments.BENCH,
                Experiments.byId(mode.experimentId!!)!!.partOf,
            )
        }
    }

    @Test
    fun `the bench covers every member and nothing else`() {
        // If an eighth measurement is ever folded in, this fails until it gets a chip.
        // Being inside a suite with no way to reach it is worse than being on the home
        // screen, which is where it was.
        val members = Experiments.membersOf(Experiments.BENCH).map { it.id }.toSet()
        val modes = Bench.MEASUREMENTS.mapNotNull { it.experimentId }.toSet()

        assertEquals(members, modes)
    }

    @Test
    fun `the notebook is not pretending to be an experiment`() {
        // It is the drawer the measurements file into, so it has no registry row and must
        // not be counted as one of them when the notebook goes looking for saved runs.
        assertNull(Bench.NOTEBOOK.experimentId)
        assertTrue(Bench.NOTEBOOK !in Bench.MEASUREMENTS)
        assertEquals(Bench.entries.size - 1, Bench.MEASUREMENTS.size)
    }

    @Test
    fun `the bench itself is a front door rather than a member of something`() {
        val bench = Experiments.byId(Experiments.BENCH)!!

        assertTrue(bench.standalone)
        assertTrue(bench.status.openable)
    }

    @Test
    fun `each measurement says what it is for, because the chip labels do not`() {
        // On the home screen these were seven cards with seven self-explaining titles.
        // Inside one screen they are seven short chips, and the sentence under them is what
        // is left of the explanation somebody used to get for free.
        Bench.entries.forEach { mode ->
            assertTrue(mode.name, mode.label.isNotBlank())
            assertTrue("${mode.name} explains itself too briefly", mode.forWhat.length > 40)
        }
    }

    @Test
    fun `path loss is the measurement the bench opens on`() {
        // The exponent it produces is the number every distance estimate in the app is
        // otherwise assuming, so it is the one to land somebody in.
        assertEquals(Bench.PATH_LOSS, Bench.entries.first())
        assertEquals(Experiments.DOPPLER, Bench.PATH_LOSS.experimentId)
    }
}
