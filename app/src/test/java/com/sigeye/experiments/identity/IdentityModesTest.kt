package com.sigeye.experiments.identity

import com.sigeye.core.Experiments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The join between the Identity screen's modes and the registry rows behind them.
 *
 * Four experiments became four modes of one screen, and each mode still draws its header,
 * its walkthrough and its limits from its own registry row. That join is the whole reason
 * the consolidation did not cost anything, and it is a string lookup, so it is exactly the
 * kind of thing that breaks quietly.
 */
class IdentityModesTest {

    @Test
    fun `every mode names a registry entry that exists`() {
        Mode.entries.forEach { mode ->
            assertNotNull(
                "${mode.name} points at \"${mode.experimentId}\", which is not in the registry",
                Experiments.byId(mode.experimentId),
            )
        }
    }

    @Test
    fun `every mode is a member of the suite it is drawn inside`() {
        // A mode whose registry row still counts as standalone would appear both as its own
        // card on the home screen and as a tab in here, which is the opposite of the point.
        Mode.entries.forEach { mode ->
            val entry = Experiments.byId(mode.experimentId)!!
            assertEquals(
                "${mode.name} is drawn inside Identity but is not marked as part of it",
                Experiments.IDENTITY,
                entry.partOf,
            )
        }
    }

    @Test
    fun `the suite covers every member and nothing else`() {
        // If a fifth experiment is ever folded in, this fails until it gets a tab. Being
        // inside a suite with no way to reach it is worse than being on the home screen.
        val members = Experiments.membersOf(Experiments.IDENTITY).map { it.id }.toSet()
        val modes = Mode.entries.map { it.experimentId }.toSet()

        assertEquals(members, modes)
    }

    @Test
    fun `the suite itself is a front door rather than a member of something`() {
        val suite = Experiments.byId(Experiments.IDENTITY)!!

        assertTrue(suite.standalone)
        assertTrue(suite.status.openable)
    }

    @Test
    fun `each mode says what it is for, because the chip labels do not`() {
        // On the home screen these were four cards with four self-explaining titles. Inside
        // one screen they are four short chips, and the sentence under them is what is left
        // of the explanation somebody used to get for free.
        Mode.entries.forEach { mode ->
            assertTrue(mode.name, mode.label.isNotBlank())
            assertTrue(mode.name, mode.forWhat.length > 30)
        }
    }

    @Test
    fun `Defeat is the mode the screen opens on`() {
        // The one that explains itself as it goes, so it is the one to land a newcomer in.
        assertEquals(Mode.DEFEAT, Mode.entries.first())
    }
}
