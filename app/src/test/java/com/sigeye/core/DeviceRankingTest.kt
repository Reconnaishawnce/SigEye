package com.sigeye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRankingTest {

    @Test
    fun `a watched device outranks everything`() {
        assertEquals(
            DeviceRanking.WATCHED,
            DeviceRanking.rank(
                watched = true,
                nickname = null,
                lists = emptySet(),
                hasIdentity = false,
            ),
        )
    }

    @Test
    fun `a nickname beats list membership, which beats a bare vendor name`() {
        val nicknamed = DeviceRanking.rank(false, "Front door", emptySet(), false)
        val listed = DeviceRanking.rank(false, null, setOf("Vehicles"), false)
        val identified = DeviceRanking.rank(false, null, emptySet(), true)
        assertTrue(nicknamed < listed)
        assertTrue(listed < identified)
    }

    @Test
    fun `anonymous hex sorts last, which is the whole point`() {
        val anonymous = DeviceRanking.rank(false, null, emptySet(), false)
        assertEquals(DeviceRanking.ANONYMOUS, anonymous)
        listOf(
            DeviceRanking.rank(true, null, emptySet(), false),
            DeviceRanking.rank(false, "x", emptySet(), false),
            DeviceRanking.rank(false, null, setOf("a"), false),
            DeviceRanking.rank(false, null, emptySet(), true),
        ).forEach { assertTrue("$it should sort above anonymous", it < anonymous) }
    }

    @Test
    fun `a blank nickname is not a nickname`() {
        // An empty string is what an abandoned rename leaves behind, and it reads as hex
        // on screen, so it must not be promoted above things that actually say something.
        assertEquals(
            DeviceRanking.ANONYMOUS,
            DeviceRanking.rank(false, "   ", emptySet(), false),
        )
        assertEquals(
            DeviceRanking.IDENTIFIED,
            DeviceRanking.rank(false, "", emptySet(), true),
        )
    }

    @Test
    fun `ranks are stable and distinct so a sort is deterministic`() {
        val all = listOf(
            DeviceRanking.WATCHED,
            DeviceRanking.NICKNAMED,
            DeviceRanking.LISTED,
            DeviceRanking.IDENTIFIED,
            DeviceRanking.ANONYMOUS,
        )
        assertEquals(all.size, all.toSet().size)
        assertEquals(all, all.sorted())
    }
}
