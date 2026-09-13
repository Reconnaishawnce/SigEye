package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceQueryTest {

    private val jbl = DeviceQuery.Subject(
        address = "AA:BB:CC:11:22:33",
        nickname = "kitchen speaker",
        name = "JBL Flip 5",
        vendor = "Harman",
        extra = "-42 dBm",
    )

    private val bose = DeviceQuery.Subject(
        address = "DD:EE:FF:44:55:66",
        name = "Bose QC45",
        vendor = "Bose Corporation",
        extra = "-71 dBm",
    )

    private val anonymous = DeviceQuery.Subject(address = "5A:00:11:22:33:44")

    private val all = listOf(jbl, bose, anonymous)

    @Test
    fun `an empty filter hides nothing`() {
        assertEquals(all, DeviceQuery.filter(all, "") { it })
        assertEquals(all, DeviceQuery.filter(all, "   ") { it })
    }

    @Test
    fun `a brand name finds the device advertising it`() {
        assertEquals(listOf(jbl), DeviceQuery.filter(all, "jbl") { it })
        assertEquals(listOf(bose), DeviceQuery.filter(all, "bose") { it })
    }

    @Test
    fun `case does not matter, because nobody types capitals into a filter`() {
        assertEquals(listOf(jbl), DeviceQuery.filter(all, "JBL") { it })
        assertEquals(listOf(jbl), DeviceQuery.filter(all, "Jbl") { it })
    }

    @Test
    fun `a nickname you gave it works as well as the name it broadcasts`() {
        assertEquals(listOf(jbl), DeviceQuery.filter(all, "kitchen") { it })
    }

    @Test
    fun `the vendor matches even when the name does not mention it`() {
        assertEquals(listOf(jbl), DeviceQuery.filter(all, "harman") { it })
    }

    @Test
    fun `an address prefix works with or without the colons`() {
        assertEquals(listOf(jbl), DeviceQuery.filter(all, "AA:BB") { it })
        assertEquals(listOf(jbl), DeviceQuery.filter(all, "aabb") { it })
        assertEquals(listOf(jbl), DeviceQuery.filter(all, "aa:bb:cc:11") { it })
    }

    @Test
    fun `a device with nothing but an address is still findable by it`() {
        assertEquals(listOf(anonymous), DeviceQuery.filter(all, "5a:00") { it })
    }

    @Test
    fun `several words all have to match, which is what makes narrowing quick`() {
        assertEquals(listOf(jbl), DeviceQuery.filter(all, "jbl 42") { it })
        assertTrue(DeviceQuery.filter(all, "jbl bose") { it }.isEmpty())
    }

    @Test
    fun `the words may match different fields`() {
        // "harman" is the vendor and "flip" is in the advertised name.
        assertEquals(listOf(jbl), DeviceQuery.filter(all, "harman flip") { it })
    }

    @Test
    fun `nonsense matches nothing rather than everything`() {
        assertTrue(DeviceQuery.filter(all, "zzzz") { it }.isEmpty())
    }

    @Test
    fun `an emptied list says what emptied it`() {
        val note = DeviceQuery.emptyNote("jbl", total = 40)
        assertTrue(note, note.contains("40"))
        assertTrue(note, note.contains("jbl"))
        assertEquals("Nothing in range yet.", DeviceQuery.emptyNote("jbl", total = 0))
    }

    @Test
    fun `a filter on an empty room does not claim the filter is at fault`() {
        assertEquals("Nothing in range yet.", DeviceQuery.emptyNote("", total = 0))
    }
}
