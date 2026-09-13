package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The running state has to stay small, because it is sorted on the main thread twice a
 * second for every device in earshot.
 *
 * Nothing bounded any of this. In a station, half an hour in, that meant hundreds of
 * thousands of packet gaps being filtered and sorted on every tick until Android decided
 * the app had stopped responding - which is what "it errors in the middle of learning a
 * device" turned out to be.
 */
class BoundedStateTest {

    private val t0 = 1_700_000_000_000L

    private val shape = AdvertShape(
        companyId = 0x004C,
        manufacturerLength = 25,
        manufacturerPrefix = "0f05",
        txPower = 12,
    )

    @Test
    fun `an address heard for hours keeps a bounded number of gaps`() {
        val address = LiveAddress(shape, isRandom = true, firstSeenMs = t0, lastSeenMs = t0)

        // An hour at five packets a second, which is an ordinary phone at close range.
        repeat(18_000) { index ->
            address.observe(-60, t0 + index * 200L)
        }

        assertTrue(
            "gaps grew to ${address.gaps.size}",
            address.gaps.size <= LiveAddress.MAX_GAPS,
        )
        assertEquals("and the interval still reads correctly", 200L, address.gaps.min())
    }

    @Test
    fun `a device that left and came back does not record the absence as an interval`() {
        // Four minutes of silence is not an advertising interval, and letting it in
        // measures absences rather than firmware.
        val address = LiveAddress(shape, isRandom = true, firstSeenMs = t0, lastSeenMs = t0)

        repeat(20) { address.observe(-60, t0 + it * 150L) }
        address.observe(-60, t0 + 4 * 60_000L)
        repeat(20) { address.observe(-60, t0 + 4 * 60_000L + it * 150L) }

        assertTrue(
            "an absence got in: ${address.gaps.max()}",
            address.gaps.all { it <= LiveAddress.MAX_GAP_MS },
        )
    }

    @Test
    fun `the best level is the best ever heard, not the best lately`() {
        // It used to be the best of the last twenty readings, which is a different number
        // and a moving one.
        val address = LiveAddress(shape, isRandom = true, firstSeenMs = t0, lastSeenMs = t0)

        address.observe(-38, t0)
        repeat(60) { address.observe(-90, t0 + (it + 1) * 150L) }

        assertEquals(-38, address.identity("AA").bestRssi)
    }

    // ------------------------------------------------------------------ forgetting

    @Test
    fun `a hunt forgets addresses it will never ask about again`() {
        val hunt = RotationHunt()
        repeat(500) { index ->
            hunt.observe(
                address = "AA:BB:CC:00:%02X:%02X".format(index / 256, index % 256),
                rssi = -70,
                atMs = t0,
                shape = shape,
                isRandom = true,
            )
        }
        assertEquals(500, hunt.addressCount)

        hunt.prune(t0 + RotationHunt.FORGET_MS + 1_000L)

        assertEquals("nothing was forgotten", 0, hunt.addressCount)
    }

    @Test
    fun `a chain tracker forgets addresses that never joined a chain`() {
        val chains = ChainTracker()
        repeat(300) { index ->
            chains.observe(
                address = "AA:BB:CC:01:%02X:%02X".format(index / 256, index % 256),
                rssi = -70,
                atMs = t0,
                shape = shape,
                isRandom = true,
            )
        }
        assertEquals(300, chains.addressCount)

        chains.prune(t0 + ChainTracker.FORGET_MS + 1_000L)

        assertEquals(0, chains.addressCount)
    }

    @Test
    fun `a watchlisted address is kept however long it has been quiet`() {
        // A seed going silent for an hour is the case the whole experiment is for.
        val chains = ChainTracker()
        chains.seed(listOf("AA:BB:CC:DD:EE:FF"))
        chains.observe("AA:BB:CC:DD:EE:FF", -70, t0, shape, isRandom = true)

        chains.prune(t0 + 2 * ChainTracker.FORGET_MS)

        assertEquals(1, chains.addressCount)
    }
}
