package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The class every identity in the app is built from, and it shipped untested.
 *
 * Two trackers share it now, and everything that claims two addresses are one device does
 * so from an [Identity] this produced. If the interval it reports is wrong, Defeating
 * Randomisation, Rotation Lab and Persistent Tracking are all wrong together and in the
 * same direction, which is the worst way to be wrong.
 */
class LiveAddressTest {

    private val shape = AdvertShape(
        companyId = 0x004C,
        serviceUuids = listOf("180F"),
        appearance = 0x0040,
        manufacturerLength = 25,
        manufacturerPrefix = "0715",
    )

    private fun live(startMs: Long = 1_000L) =
        LiveAddress(shape, isRandom = true, firstSeenMs = startMs, lastSeenMs = startMs)

    @Test
    fun `the first reading is not a gap`() {
        // A gap needs two packets. Counting one from nothing would put a zero into every
        // interval estimate a device ever produces.
        val entry = live()
        entry.observe(-60, 1_000L)
        assertEquals(1, entry.packets)
        assertTrue(entry.gaps.isEmpty())
    }

    @Test
    fun `gaps are the time between consecutive packets`() {
        val entry = live()
        listOf(1_000L, 1_150L, 1_300L, 1_450L).forEach { entry.observe(-60, it) }
        assertEquals(listOf(150L, 150L, 150L), entry.gaps)
        assertEquals(4, entry.packets)
        assertEquals(1_450L, entry.lastSeenMs)
    }

    @Test
    fun `the identity reports the interval the device actually advertises at`() {
        val entry = live()
        var at = 1_000L
        repeat(40) {
            entry.observe(-60, at)
            at += 152L
        }
        val identity = entry.identity("AA:BB:CC:DD:EE:FF")
        assertEquals(152L, identity.medianGapMs)
        // 0.625 ms slots, so 152 ms is 243 of them - the number a researcher comparing two
        // devices actually wants.
        assertEquals(243, identity.intervalSlots)
    }

    @Test
    fun `a missed packet does not drag the interval estimate up`() {
        // The scanner misses packets constantly, so most gaps are two or three intervals.
        // A median would land on whichever multiple happened to dominate.
        val entry = live()
        var at = 1_000L
        repeat(40) { index ->
            entry.observe(-60, at)
            at += if (index % 3 == 0) 304L else 152L
        }
        assertEquals(152L, entry.identity("AA").medianGapMs)
    }

    @Test
    fun `signal is averaged over the recent past, not over all time`() {
        val entry = live()
        var at = 1_000L
        // Forty readings at -90, then twenty at -50. The window holds twenty, so the mean
        // should be the recent ones only - a device that has just come close should read
        // as close.
        repeat(40) { entry.observe(-90, at); at += 100 }
        repeat(20) { entry.observe(-50, at); at += 100 }
        assertEquals(-50.0, entry.recentRssi, 0.001)
        assertEquals(-50, entry.identity("AA").bestRssi)
    }

    @Test
    fun `an address nothing has been heard from reports no signal rather than zero`() {
        // Zero dBm is a very loud device. Silence has to be some other number.
        val entry = live()
        assertEquals(-127.0, entry.recentRssi, 0.001)
        assertEquals(-127, entry.lastRssi)
    }

    @Test
    fun `the identity carries through what it was told about the address`() {
        val entry = live(startMs = 5_000L)
        entry.observe(-70, 5_000L)
        entry.observe(-70, 5_200L)
        val identity = entry.identity("5A:11:22:33:44:55")
        assertEquals("5A:11:22:33:44:55", identity.address)
        assertEquals(shape, identity.shape)
        assertTrue(identity.isRandom)
        assertEquals(5_000L, identity.firstSeenMs)
        assertEquals(5_200L, identity.lastSeenMs)
        assertEquals(2, identity.packets)
    }

    @Test
    fun `a metronomic device reads as tighter than an erratic one`() {
        val steady = live()
        val erratic = live()
        var at = 1_000L
        repeat(40) {
            steady.observe(-60, at)
            at += 152L
        }
        at = 1_000L
        listOf(100L, 200L, 140L, 180L, 120L, 210L, 150L, 170L).let { pattern ->
            repeat(40) { index ->
                erratic.observe(-60, at)
                at += pattern[index % pattern.size]
            }
        }
        assertTrue(
            steady.identity("A").intervalJitter <= erratic.identity("B").intervalJitter,
        )
    }

    @Test
    fun `a device sitting still reads as steadier than one being carried`() {
        val still = live()
        val moving = live()
        var at = 1_000L
        repeat(30) {
            still.observe(-60, at)
            moving.observe(-40 - (it % 20) * 2, at)
            at += 150L
        }
        assertTrue(still.identity("A").rssiSpread < moving.identity("B").rssiSpread)
    }

    @Test
    fun `a better look at the advertisement replaces a poorer one`() {
        // The first packet from a device is often a bare header; the interesting one comes
        // later. Keeping the richest is what makes a shape worth matching on.
        val entry = live()
        val bare = AdvertShape(companyId = 0x004C)
        val rich = shape
        assertTrue(rich.distinctiveness > bare.distinctiveness)
        entry.shape = bare
        if (rich.distinctiveness > entry.shape.distinctiveness) entry.shape = rich
        assertEquals(rich, entry.shape)
    }
}
