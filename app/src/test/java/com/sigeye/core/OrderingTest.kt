package com.sigeye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderingTest {

    private val available = setOf("radar", "train", "rotation", "forensics", "discovery")

    @Test
    fun `a favorite pointing at an experiment that no longer exists is dropped`() {
        // Survives an app update that renamed or removed something. Otherwise it is an
        // untappable row with no title, and only people on older versions ever see it.
        assertEquals(
            listOf("radar", "train"),
            Ordering.sanitise(listOf("radar", "ghost", "train"), available),
        )
    }

    @Test
    fun `a list that got duplicated is repaired rather than shown twice`() {
        assertEquals(
            listOf("radar", "train"),
            Ordering.sanitise(listOf("radar", "train", "radar"), available),
        )
    }

    @Test
    fun `the seed keeps the recommended order, not the registry's`() {
        assertEquals(
            listOf("rotation", "train", "radar"),
            Ordering.seed(listOf("rotation", "train", "radar"), available),
        )
    }

    @Test
    fun `a recommendation for something not shipped is quietly skipped`() {
        assertEquals(
            listOf("radar"),
            Ordering.seed(listOf("radar", "not-built-yet"), available),
        )
    }

    // ------------------------------------------------------------------ starring

    @Test
    fun `starring adds, and starring again removes`() {
        val once = Ordering.toggle(listOf("radar"), "train")
        assertEquals(listOf("radar", "train"), once)
        assertEquals(listOf("radar"), Ordering.toggle(once, "train"))
    }

    @Test
    fun `a new favorite goes to the bottom, leaving an arranged list arranged`() {
        // Inserting at the top would quietly rearrange the order somebody had chosen,
        // every time they starred something else.
        val arranged = listOf("forensics", "radar", "train")
        assertEquals(
            listOf("forensics", "radar", "train", "discovery"),
            Ordering.toggle(arranged, "discovery"),
        )
    }

    @Test
    fun `unstarring the only favorite leaves an empty list, not the seed again`() {
        assertTrue(Ordering.toggle(listOf("radar"), "radar").isEmpty())
    }

    // ---------------------------------------------------------------- reordering

    @Test
    fun `moving up swaps with the one above`() {
        assertEquals(
            listOf("radar", "train", "rotation"),
            Ordering.moveUp(listOf("train", "radar", "rotation"), "radar"),
        )
    }

    @Test
    fun `moving down swaps with the one below`() {
        assertEquals(
            listOf("radar", "train", "rotation"),
            Ordering.moveDown(listOf("train", "radar", "rotation"), "train"),
        )
    }

    @Test
    fun `the top cannot go up and the bottom cannot go down`() {
        val list = listOf("train", "radar", "rotation")
        assertEquals(list, Ordering.moveUp(list, "train"))
        assertEquals(list, Ordering.moveDown(list, "rotation"))
        assertTrue(!Ordering.canMoveUp(list, "train"))
        assertTrue(!Ordering.canMoveDown(list, "rotation"))
        assertTrue(Ordering.canMoveDown(list, "train"))
        assertTrue(Ordering.canMoveUp(list, "rotation"))
    }

    @Test
    fun `moving something that is not in the list changes nothing`() {
        val list = listOf("train", "radar")
        assertEquals(list, Ordering.moveUp(list, "forensics"))
        assertEquals(list, Ordering.moveDown(list, "forensics"))
        assertTrue(!Ordering.canMoveUp(list, "forensics"))
        assertTrue(!Ordering.canMoveDown(list, "forensics"))
    }

    @Test
    fun `a single favorite has nowhere to go`() {
        val list = listOf("radar")
        assertEquals(list, Ordering.moveUp(list, "radar"))
        assertEquals(list, Ordering.moveDown(list, "radar"))
    }

    // --------------------------------------------------------------------- merging

    @Test
    fun `a panel added by a later version appears rather than staying invisible`() {
        // Somebody arranged this screen before "diagnostics" existed. Their arrangement
        // has to survive and the new panel has to show up, or it is invisible to exactly
        // the people who have used the app longest.
        val stored = listOf("chart", "count")
        val available = listOf("count", "chart", "diagnostics")
        assertEquals(listOf("chart", "count", "diagnostics"), Ordering.merge(stored, available))
    }

    @Test
    fun `a panel removed by a later version drops out of a stored arrangement`() {
        val stored = listOf("chart", "gone", "count")
        assertEquals(
            listOf("chart", "count"),
            Ordering.merge(stored, listOf("count", "chart")),
        )
    }

    @Test
    fun `nothing stored means the order the screen ships with`() {
        val available = listOf("count", "chart", "diagnostics")
        assertEquals(available, Ordering.merge(emptyList(), available))
    }

    @Test
    fun `merging never loses or duplicates a panel`() {
        val available = listOf("a", "b", "c", "d")
        val merged = Ordering.merge(listOf("d", "b", "ghost", "b"), available)
        assertEquals(available.toSet(), merged.toSet())
        assertEquals(available.size, merged.size)
    }

    @Test
    fun `reordering never loses or invents an entry`() {
        var list = listOf("a", "b", "c", "d")
        repeat(10) {
            list = Ordering.moveDown(list, "a")
            list = Ordering.moveUp(list, "d")
        }
        assertEquals(4, list.size)
        assertEquals(setOf("a", "b", "c", "d"), list.toSet())
    }
}
