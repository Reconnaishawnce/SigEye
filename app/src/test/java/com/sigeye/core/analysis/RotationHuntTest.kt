package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationHuntTest {

    private val shape = AdvertShape(
        companyId = 0x004C,
        serviceUuids = listOf("180f", "180a"),
        name = "Phone",
        manufacturerLength = 25,
        manufacturerPrefix = "0215",
    )

    private fun hunt() = RotationHunt(learnMs = 10_000L, silenceMs = 20_000L)

    /** Advertises at a steady interval across a window. */
    private fun RotationHunt.advertise(
        address: String,
        fromMs: Long,
        toMs: Long,
        rssi: Int = -55,
        intervalMs: Long = 100L,
        shape: AdvertShape = this@RotationHuntTest.shape,
        isRandom: Boolean = true,
    ) {
        var at = fromMs
        while (at <= toMs) {
            observe(address, rssi, at, shape, isRandom)
            at += intervalMs
        }
    }

    // ------------------------------------------------------------- the learning

    @Test
    fun `learning finishes on time and produces a fingerprint`() {
        val hunt = hunt()
        hunt.advertise("AA", 0L, 9_000L)
        hunt.track("AA", 0L)
        hunt.tick(5_000L)
        assertEquals(HuntStage.LEARN, hunt.state(5_000L).stage)

        hunt.tick(10_000L)
        val state = hunt.state(10_000L)
        assertEquals(HuntStage.WATCH, state.stage)
        assertEquals("AA", state.tracking)
        assertEquals(100L, state.intervalMs)
        assertTrue(state.packets > 50)
    }

    @Test
    fun `only devices with enough packets are offered to follow`() {
        val hunt = hunt()
        hunt.advertise("CHATTY", 0L, 5_000L)
        hunt.observe("BRIEF", -70, 1_000L, shape, true)

        val candidates = hunt.candidates(5_000L)
        assertTrue(candidates.any { it.address == "CHATTY" })
        assertTrue(candidates.none { it.address == "BRIEF" })
    }

    // ------------------------------------------------------------ the rotation

    @Test
    fun `a rotation is detected and explained`() {
        val hunt = hunt()
        hunt.advertise("AA", 0L, 30_000L)
        hunt.track("AA", 0L)
        hunt.tick(12_000L)

        // The old address stops; a new one with the same fingerprint starts.
        hunt.advertise("BB", 32_000L, 60_000L)
        hunt.tick(60_000L)

        val state = hunt.state(60_000L)
        assertEquals(1, state.rotations.size)
        val rotation = state.rotations.first()
        assertEquals("AA", rotation.fromAddress)
        assertEquals("BB", rotation.toAddress)
        assertEquals("BB", state.tracking)
        assertTrue(rotation.score.confidence != LinkConfidence.NONE)
        assertTrue(rotation.score.supporting.isNotEmpty())
    }

    @Test
    fun `an unrelated device is not claimed as the rotation`() {
        val hunt = hunt()
        hunt.advertise("AA", 0L, 30_000L)
        hunt.track("AA", 0L)
        hunt.tick(12_000L)

        // Something completely different starts up instead.
        hunt.advertise(
            "STRANGER",
            32_000L,
            60_000L,
            rssi = -90,
            intervalMs = 1_000L,
            shape = AdvertShape(companyId = 0x0006, name = "Kettle"),
        )
        hunt.tick(60_000L)

        assertTrue(hunt.state(60_000L).rotations.isEmpty())
        assertEquals("AA", hunt.state(60_000L).tracking)
    }

    @Test
    fun `a fixed address is never claimed as a rotation`() {
        val hunt = hunt()
        hunt.advertise("AA", 0L, 30_000L)
        hunt.track("AA", 0L)
        hunt.tick(12_000L)
        hunt.advertise("FIXED", 32_000L, 60_000L, isRandom = false)
        hunt.tick(60_000L)

        assertTrue(hunt.state(60_000L).rotations.isEmpty())
    }

    @Test
    fun `nothing is claimed while the tracked address is still talking`() {
        val hunt = hunt()
        hunt.advertise("AA", 0L, 60_000L)
        hunt.track("AA", 0L)
        hunt.advertise("BB", 30_000L, 60_000L)
        hunt.tick(60_000L)

        assertTrue(hunt.state(60_000L).rotations.isEmpty())
        assertEquals("AA", hunt.state(60_000L).tracking)
    }

    @Test
    fun `the address it started from is never claimed as its own successor`() {
        val hunt = hunt()
        hunt.advertise("AA", 0L, 30_000L)
        hunt.track("AA", 0L)
        hunt.tick(12_000L)
        hunt.advertise("BB", 32_000L, 60_000L)
        hunt.tick(60_000L)
        // The original comes back, as a device that stopped and restarted would.
        hunt.advertise("AA", 90_000L, 120_000L)
        hunt.tick(120_000L)

        assertTrue(hunt.state(120_000L).rotations.none { it.toAddress == "AA" })
    }

    // ---------------------------------------------------------- the walk-away

    @Test
    fun `carrying the device away confirms the link`() {
        val hunt = hunt()
        hunt.advertise("AA", 0L, 30_000L, rssi = -50)
        hunt.track("AA", 0L)
        hunt.tick(12_000L)
        hunt.advertise("BB", 32_000L, 60_000L, rssi = -50)
        hunt.tick(60_000L)

        hunt.startWalkTest()
        assertTrue(hunt.walkInProgress)
        // Walking away: the signal falls right off.
        hunt.advertise("BB", 61_000L, 70_000L, rssi = -85)
        val proof = hunt.finishWalkTest()

        assertNotNull(proof)
        assertTrue(proof!!.confirmed)
        assertTrue(proof.dropDb >= RotationHunt.CONFIRMING_DROP_DB)
        assertTrue(hunt.state(70_000L).rotations.first().confirmed)
        assertEquals(1, hunt.state(70_000L).confirmedRotations)
    }

    @Test
    fun `a signal that does not budge disproves the link`() {
        // The result that matters most: the app said this address was your phone, you
        // carried your phone two rooms away, and it carried on regardless.
        val hunt = hunt()
        hunt.advertise("AA", 0L, 30_000L, rssi = -50)
        hunt.track("AA", 0L)
        hunt.tick(12_000L)
        hunt.advertise("BB", 32_000L, 60_000L, rssi = -50)
        hunt.tick(60_000L)

        hunt.startWalkTest()
        hunt.advertise("BB", 61_000L, 70_000L, rssi = -52)
        val proof = hunt.finishWalkTest()!!

        assertTrue(!proof.confirmed)
        assertTrue(proof.note.contains("would mean the link was wrong"))
        assertTrue(hunt.state(70_000L).rotations.first().disproved)
    }

    @Test
    fun `going out of range entirely counts as confirmation`() {
        // The clearest possible result. Refusing to credit silence would fail it.
        val hunt = hunt()
        hunt.advertise("AA", 0L, 30_000L, rssi = -50)
        hunt.track("AA", 0L)
        hunt.tick(12_000L)

        hunt.startWalkTest()
        val proof = hunt.finishWalkTest()!!
        assertTrue(proof.confirmed)
        assertEquals(0, proof.samples)
        assertTrue(proof.note.contains("stopped being heard"))
    }

    @Test
    fun `finishing a test that was never started returns nothing`() {
        assertNull(hunt().finishWalkTest())
    }

    @Test
    fun `resetting forgets the hunt entirely`() {
        val hunt = hunt()
        hunt.advertise("AA", 0L, 30_000L)
        hunt.track("AA", 0L)
        hunt.tick(12_000L)
        hunt.reset()

        val state = hunt.state(40_000L)
        assertEquals(HuntStage.PICK, state.stage)
        assertNull(state.tracking)
        assertTrue(state.rotations.isEmpty())
    }
}
