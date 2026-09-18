package com.sigeye.core.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the hub reports about its own ability to keep up. */
class ScanHealthTest {

    @Test
    fun `a deep decode queue is a crowd, whatever the rate says`() {
        // The direct evidence. Packets are arriving faster than they can be decoded, which
        // is the condition where results start being dropped.
        val health = ScanHealth(scanning = true, backlog = ScanHealth.BUSY_BACKLOG + 1)

        assertTrue(health.crowded)
    }

    @Test
    fun `hundreds of packets a second is a crowd before the queue builds`() {
        // The early warning, so a screen can explain itself before anything is lost.
        val health = ScanHealth(scanning = true, advertsPerSecond = ScanHealth.BUSY_RATE)

        assertTrue(health.crowded)
    }

    @Test
    fun `an ordinary room is not a crowd`() {
        val health = ScanHealth(scanning = true, advertsPerSecond = 30.0, backlog = 4)

        assertFalse(health.crowded)
    }

    @Test
    fun `nothing is crowded while the scan is stopped`() {
        // A stale backlog left over from a scan that has since stopped describes the past.
        val health = ScanHealth(scanning = false, backlog = 9_000, advertsPerSecond = 900.0)

        assertFalse(health.crowded)
        assertFalse(health.starved)
    }

    @Test
    fun `starvation and crowding are different questions`() {
        // The distinction the airport exposed: throughput can look perfect while the one
        // device being followed has gone silent, because rotating addresses keep the total
        // high. Crowded says the phone is saturated; starved says delivery collapsed.
        val busy = ScanHealth(
            scanning = true,
            advertsPerSecond = 900.0,
            referenceRate = 900.0,
            backlog = 2_000,
        )

        assertTrue(busy.crowded)
        assertFalse(busy.starved)
    }
}
