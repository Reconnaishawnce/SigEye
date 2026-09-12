package com.sigeye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouritesTest {

    private val available = setOf("radar", "train", "rotation", "forensics", "discovery")

    @Test
    fun `a favourite pointing at an experiment that no longer exists is dropped`() {
        // Survives an app update that renamed or removed something. Otherwise it is an
        // untappable row with no title, and only people on older versions ever see it.
        assertEquals(
            listOf("radar", "train"),
            Favourites.sanitise(listOf("radar", "ghost", "train"), available),
        )
    }

    @Test
    fun `a list that got duplicated is repaired rather than shown twice`() {
        assertEquals(
            listOf("radar", "train"),
            Favourites.sanitise(listOf("radar", "train", "radar"), available),
        )
    }

    @Test
    fun `the seed keeps the recommended order, not the registry's`() {
        assertEquals(
            listOf("rotation", "train", "radar"),
            Favourites.seed(listOf("rotation", "train", "radar"), available),
        )
    }

    @Test
    fun `a recommendation for something not shipped is quietly skipped`() {
        assertEquals(
            listOf("radar"),
            Favourites.seed(listOf("radar", "not-built-yet"), available),
        )
    }

    // ------------------------------------------------------------------ starring

    @Test
    fun `starring adds, and starring again removes`() {
        val once = Favourites.toggle(listOf("radar"), "train")
        assertEquals(listOf("radar", "train"), once)
        assertEquals(listOf("radar"), Favourites.toggle(once, "train"))
    }

    @Test
    fun `a new favourite goes to the bottom, leaving an arranged list arranged`() {
        // Inserting at the top would quietly rearrange the order somebody had chosen,
        // every time they starred something else.
        val arranged = listOf("forensics", "radar", "train")
        assertEquals(
            listOf("forensics", "radar", "train", "discovery"),
            Favourites.toggle(arranged, "discovery"),
        )
    }

    @Test
    fun `unstarring the only favourite leaves an empty list, not the seed again`() {
        assertTrue(Favourites.toggle(listOf("radar"), "radar").isEmpty())
    }

    // ---------------------------------------------------------------- reordering

    @Test
    fun `moving up swaps with the one above`() {
        assertEquals(
            listOf("radar", "train", "rotation"),
            Favourites.moveUp(listOf("train", "radar", "rotation"), "radar"),
        )
    }

    @Test
    fun `moving down swaps with the one below`() {
        assertEquals(
            listOf("radar", "train", "rotation"),
            Favourites.moveDown(listOf("train", "radar", "rotation"), "train"),
        )
    }

    @Test
    fun `the top cannot go up and the bottom cannot go down`() {
        val list = listOf("train", "radar", "rotation")
        assertEquals(list, Favourites.moveUp(list, "train"))
        assertEquals(list, Favourites.moveDown(list, "rotation"))
        assertTrue(!Favourites.canMoveUp(list, "train"))
        assertTrue(!Favourites.canMoveDown(list, "rotation"))
        assertTrue(Favourites.canMoveDown(list, "train"))
        assertTrue(Favourites.canMoveUp(list, "rotation"))
    }

    @Test
    fun `moving something that is not in the list changes nothing`() {
        val list = listOf("train", "radar")
        assertEquals(list, Favourites.moveUp(list, "forensics"))
        assertEquals(list, Favourites.moveDown(list, "forensics"))
        assertTrue(!Favourites.canMoveUp(list, "forensics"))
        assertTrue(!Favourites.canMoveDown(list, "forensics"))
    }

    @Test
    fun `a single favourite has nowhere to go`() {
        val list = listOf("radar")
        assertEquals(list, Favourites.moveUp(list, "radar"))
        assertEquals(list, Favourites.moveDown(list, "radar"))
    }

    @Test
    fun `reordering never loses or invents an entry`() {
        var list = listOf("a", "b", "c", "d")
        repeat(10) {
            list = Favourites.moveDown(list, "a")
            list = Favourites.moveUp(list, "d")
        }
        assertEquals(4, list.size)
        assertEquals(setOf("a", "b", "c", "d"), list.toSet())
    }
}
