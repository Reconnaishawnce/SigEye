package com.sigeye.experiments.watchlist

import com.sigeye.core.analysis.surveillance.Certainty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to somebody's watch rules when the app learns a new signature.
 *
 * Written because the Flock rules would otherwise have reached nobody. Rules are seeded on
 * first run and never touched again, so every existing install would have kept watching for
 * exactly what it was watching for the day it was set up.
 */
class WatchDefaultsTest {

    private val fresh = emptySet<String>()

    private fun ids(rules: List<WatchRule>) = rules.map { it.id }.toSet()

    @Test
    fun `an install that has never seen a default gets all of them`() {
        val adopted = WatchDefaults.adopt(emptyList(), fresh)

        assertEquals(ids(WatchRule.defaults()), ids(adopted))
    }

    @Test
    fun `a rule somebody deleted on purpose does not come back`() {
        // The app arguing with its user once a version is worse than a missing rule.
        val offered = WatchDefaults.offeredAfter(fresh)

        val adopted = WatchDefaults.adopt(emptyList(), offered)

        assertTrue(adopted.isEmpty())
    }

    @Test
    fun `an existing install gains only what is genuinely new to it`() {
        // Somebody who set the app up before the surveillance rule existed. Their old rule
        // had been offered; the new one had not.
        val theirs = listOf(
            WatchRule.RETIRED_AXON_OUI.copy(minRssi = -70, label = "Axon, close by"),
        )

        val adopted = WatchDefaults.adopt(theirs, setOf(WatchRule.RETIRED_AXON_OUI.id))

        assertTrue("surveillance-known" in ids(adopted))
        assertTrue("their edited rule was thrown away", theirs.first() in adopted)
    }

    @Test
    fun `an untouched superseded rule is replaced rather than left to double up`() {
        // The old Axon rule and the new one both fire on 00:25:DF, so leaving both in place
        // means two notifications for one body camera.
        val adopted = WatchDefaults.adopt(listOf(WatchRule.RETIRED_AXON_OUI), fresh)

        assertFalse(WatchRule.RETIRED_AXON_OUI in adopted)
        assertTrue("surveillance-known" in ids(adopted))
    }

    @Test
    fun `a customized copy of the superseded rule is left alone`() {
        // Anything somebody changed is theirs. A threshold chosen deliberately is not ours
        // to discard because we shipped something we like better.
        val edited = WatchRule.RETIRED_AXON_OUI.copy(cooldownSeconds = 30)

        val adopted = WatchDefaults.adopt(listOf(edited), fresh)

        assertTrue(edited in adopted)
    }

    @Test
    fun `nothing changes on a launch with nothing new to offer`() {
        val settled = WatchRule.defaults()
        val offered = WatchDefaults.offeredAfter(fresh)

        assertEquals(settled, WatchDefaults.adopt(settled, offered))
    }

    @Test
    fun `the default watch covers hardware the app can actually name`() {
        val rule = WatchRule.defaults().single { it.id == "surveillance-known" }

        assertEquals(MatchKind.SURVEILLANCE, rule.kind)
        assertEquals(Certainty.LIKELY, WatchRule.floorOf(rule.value))
        assertTrue(rule.enabled)
    }

    @Test
    fun `an unreadable certainty falls back to the strictest rather than the loosest`() {
        // A corrupt or hand-edited value must not turn every maybe into an alert.
        assertEquals(Certainty.CONFIRMED, WatchRule.floorOf("nonsense"))
        assertEquals(Certainty.CONFIRMED, WatchRule.floorOf(""))
        assertEquals(Certainty.POSSIBLE, WatchRule.floorOf("possible"))
    }

    @Test
    fun `every default has a distinct id because ids are how they are remembered`() {
        val all = WatchRule.defaults().map { it.id }

        assertEquals(all.size, all.toSet().size)
    }
}
