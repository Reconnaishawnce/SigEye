package com.sigeye.core.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassQualityTest {

    @Test
    fun `printing a bearing needs a compass that has said it is well`() {
        assertTrue(CompassQuality.HIGH.isUsable)
        assertTrue(CompassQuality.MEDIUM.isUsable)
        assertFalse(CompassQuality.LOW.isUsable)
        assertFalse(CompassQuality.UNKNOWN.isUsable)
        assertFalse(CompassQuality.UNRELIABLE.isUsable)
        assertFalse(CompassQuality.ABSENT.isUsable)
    }

    @Test
    fun `recording a sweep needs only that a compass exists`() {
        // The bug this encodes: the strict gate was used for capture too, so a compass
        // dipping to LOW mid-turn - which is most turns taken indoors - silently dropped
        // the rest of the sweep while the needle carried on moving.
        CompassQuality.entries.filter { it != CompassQuality.ABSENT }.forEach {
            assertTrue("$it should still record", it.isUsableForSweep)
        }
        assertFalse(CompassQuality.ABSENT.isUsableForSweep)
    }

    @Test
    fun `an absent accuracy report ranks above a bad one, not below it`() {
        // Plenty of phones never report an accuracy for the fused rotation vector at all.
        // Ranking UNKNOWN at the bottom would put a "your compass is poor" warning on every
        // sweep those phones ever take.
        assertTrue(CompassQuality.UNKNOWN.rank > CompassQuality.LOW.rank)
        assertTrue(CompassQuality.UNKNOWN.rank > CompassQuality.UNRELIABLE.rank)
        assertTrue(CompassQuality.UNKNOWN.rank < CompassQuality.MEDIUM.rank)
    }

    @Test
    fun `rank runs strictly worst to best`() {
        val order = listOf(
            CompassQuality.ABSENT,
            CompassQuality.UNRELIABLE,
            CompassQuality.LOW,
            CompassQuality.UNKNOWN,
            CompassQuality.MEDIUM,
            CompassQuality.HIGH,
        )
        assertEquals(CompassQuality.entries.size, order.size)
        order.zipWithNext().forEach { (worse, better) ->
            assertTrue("$worse should rank below $better", worse.rank < better.rank)
        }
    }
}
