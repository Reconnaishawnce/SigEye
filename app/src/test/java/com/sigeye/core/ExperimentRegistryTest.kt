package com.sigeye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is the one place a mistake is invisible until somebody taps the thing.
 *
 * An experiment with no screen, a favorite pointing nowhere, a duplicate id - all of
 * them compile, ship, and then do nothing on a phone. These are cheap to check and there
 * was no reason not to.
 */
class ExperimentRegistryTest {

    private val ready = Experiments.all.filter { it.status.openable }

    @Test
    fun `ids are unique, so one experiment cannot shadow another`() {
        val ids = Experiments.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `every ready experiment has a reason for the favorites card`() {
        // Five hand-written reasons and twenty-three fallbacks was visible the moment
        // anybody starred a sixth thing.
        val missing = ready.filter { Experiments.featuredReasons[it.id] == null }
        assertTrue("no reason for: " + missing.map { it.id }, missing.isEmpty())
    }

    @Test
    fun `no reason is written for an experiment that does not exist`() {
        val ids = Experiments.all.map { it.id }.toSet()
        val orphans = Experiments.featuredReasons.keys.filterNot { it in ids }
        assertTrue("reasons with no experiment: $orphans", orphans.isEmpty())
    }

    @Test
    fun `every seeded favorite is a ready experiment`() {
        Experiments.featuredIds.forEach { id ->
            val experiment = Experiments.byId(id)
            assertNotNull("seeded favorite $id does not exist", experiment)
            assertTrue("seeded favorite $id is not openable", experiment!!.status.openable)
        }
    }

    @Test
    fun `every ready experiment explains itself`() {
        // The house rule is that an experiment says what it needs, how to run it and
        // where it lies to you. A ready one with an empty howTo is half-finished.
        ready.forEach { experiment ->
            assertTrue("${experiment.id} has no howTo", experiment.howTo.isNotEmpty())
            assertTrue("${experiment.id} has no reading", !experiment.reading.isNullOrBlank())
            assertTrue("${experiment.id} has no limits", !experiment.limits.isNullOrBlank())
        }
    }

    @Test
    fun `every experiment belongs to a category that is actually shown`() {
        val shown = Experiments.byCategory().flatMap { it.second }.map { it.id }.toSet()
        val standalone = Experiments.all.filter { it.standalone }.map { it.id }.toSet()

        assertEquals(standalone, shown)
    }

    @Test
    fun `nothing is unreachable, so a mode of a suite is still openable`() {
        // The consolidation moved four experiments off the home screen and into one
        // screen. Off the home screen has to mean inside something, not gone.
        Experiments.all.filterNot { it.standalone }.forEach { member ->
            val suite = Experiments.byId(member.partOf!!)
            assertNotNull("${member.id} claims to be part of ${member.partOf}", suite)
            assertTrue(
                "${member.id} is inside ${suite!!.id}, which is not itself reachable",
                suite.standalone,
            )
            assertTrue(
                "${member.id} is a mode of a suite nobody can open",
                suite.status.openable,
            )
        }
    }

    @Test
    fun `a suite keeps its modes explaining themselves separately`() {
        // The point of consolidating is one card instead of four, not four descriptions
        // collapsing into one. Each mode still teaches what it taught.
        val modes = Experiments.membersOf(Experiments.IDENTITY)

        assertEquals(4, modes.size)
        modes.forEach { mode ->
            assertTrue(mode.id, mode.teaches.isNotBlank())
            assertTrue(mode.id, mode.howTo.isNotEmpty())
            assertTrue(mode.id, mode.limits != null || mode.reading != null)
        }
    }

    @Test
    fun `the ready count matches the registry`() {
        assertEquals(ready.size, Experiments.readyCount)
    }

    // ------------------------------------------------------------------------ tiers

    @Test
    fun `every experiment sits in exactly one tier and they add up`() {
        val total = Experiment.Status.entries.sumOf { Experiments.count(it) }
        assertEquals(Experiments.all.size, total)
    }

    @Test
    fun `only in-development experiments refuse to open`() {
        Experiments.all.forEach { experiment ->
            assertEquals(
                experiment.id,
                experiment.status != Experiment.Status.DEVELOPMENT,
                experiment.status.openable,
            )
        }
    }

    @Test
    fun `active experiments wear no badge and the other two do`() {
        // The badge is the whole point of the tier on a card: active is the unremarkable
        // case and should look like it.
        assertNull(Experiment.Status.ACTIVE.badge)
        assertNotNull(Experiment.Status.BETA.badge)
        assertNotNull(Experiment.Status.DEVELOPMENT.badge)
    }

    @Test
    fun `a category lists its proven experiments before its unproven ones`() {
        Experiments.byCategory().forEach { (category, experiments) ->
            val tiers = experiments.map { it.status.ordinal }
            assertEquals(category.label, tiers.sorted(), tiers)
        }
    }

    @Test
    fun `nothing in development is seeded as a favorite`() {
        Experiments.featuredIds.forEach { id ->
            val experiment = Experiments.byId(id)!!
            assertTrue("$id is not built", experiment.status.openable)
        }
    }
}
