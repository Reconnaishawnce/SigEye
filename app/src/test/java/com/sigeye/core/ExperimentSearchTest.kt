package com.sigeye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentSearchTest {

    @Test
    fun `an empty search offers nothing rather than everything`() {
        // Twenty-eight cards is what the page already is. A search box that returns the
        // whole list for a blank query is a search box that does nothing.
        assertTrue(Experiments.search("").isEmpty())
        assertTrue(Experiments.search("   ").isEmpty())
    }

    @Test
    fun `a title match is found`() {
        val found = Experiments.search("microwave")
        assertTrue(found.any { it.id == Experiments.MICROWAVE })
    }

    @Test
    fun `searching is case insensitive`() {
        assertEquals(
            Experiments.search("MICROWAVE").map { it.id },
            Experiments.search("microwave").map { it.id },
        )
    }

    @Test
    fun `a word only in the blurb is still found`() {
        // "Crisp packet" appears in what Faraday Cage Test does, nowhere in its name.
        // Somebody looking for that is not going to search for "Faraday".
        val found = Experiments.search("crisp")
        assertTrue(found.map { it.id }.toString(), found.any { it.id == Experiments.FARADAY })
    }

    @Test
    fun `a word only in the teaching is still found`() {
        val found = Experiments.search("waveguide") + Experiments.search("multipath")
        assertTrue("nothing matched a teaching-only word", found.isNotEmpty())
    }

    @Test
    fun `a title that starts with the search comes before one that merely contains it`() {
        val found = Experiments.search("train")
        assertTrue(found.isNotEmpty())
        assertEquals(Experiments.TRAIN_SPOTTER, found.first().id)
    }

    @Test
    fun `working experiments are offered before unbuilt ones`() {
        // A search that puts a dead end above something that opens is offering a dead end.
        Experiments.search("signal").let { found ->
            val tiers = found.map { it.status.ordinal }
            assertEquals(tiers.sorted(), tiers)
        }
    }

    @Test
    fun `nonsense matches nothing rather than everything`() {
        assertTrue(Experiments.search("zzzqqq").isEmpty())
    }

    // ------------------------------------------------------------------- the wish list

    @Test
    fun `the wish list is exactly what cannot be opened`() {
        val unbuilt = Experiments.unbuilt()
        assertTrue(unbuilt.isNotEmpty())
        assertTrue(unbuilt.none { it.status.openable })
        assertEquals(
            Experiments.count(Experiment.Status.DEVELOPMENT),
            unbuilt.size,
        )
    }

    @Test
    fun `the categories can be listed without the unbuilt ones in them`() {
        val shown = Experiments.byCategory(includeUnbuilt = false)
            .flatMap { it.second }
        assertTrue(shown.isNotEmpty())
        assertTrue(shown.all { it.status.openable })
        // And together with the wish list, nothing has gone missing.
        assertEquals(Experiments.all.size, shown.size + Experiments.unbuilt().size)
    }

    @Test
    fun `every wish list entry still explains why it is wanted`() {
        // The wish list is read, not skipped past - it is the thing people are invited to
        // argue with. An entry with no explanation is a teaser.
        Experiments.unbuilt().forEach { experiment ->
            assertTrue(experiment.id, experiment.blurb.isNotBlank())
            assertTrue(experiment.id, experiment.teaches.isNotBlank())
        }
    }
}
