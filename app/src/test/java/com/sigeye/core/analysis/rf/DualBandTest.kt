package com.sigeye.core.analysis.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualBandTest {

    private fun radio(bssid: String, ssid: String?, mhz: Int, rssi: Double, scans: Int = 5) =
        Radio(bssid, ssid, mhz, rssi, scans)

    // ---------------------------------------------------------------- pairing radios

    @Test
    fun `radios differing only in the last octet are one access point`() {
        val pairs = DualBand.pair(
            listOf(
                radio("AA:BB:CC:DD:EE:01", "Home", 2437, -45.0),
                radio("AA:BB:CC:DD:EE:02", "Home", 5180, -52.0),
            ),
        )
        assertEquals(1, pairs.size)
        assertTrue(pairs.first().sameHardware)
        assertEquals(2437, pairs.first().low.frequencyMhz)
        assertEquals(5180, pairs.first().high.frequencyMhz)
    }

    @Test
    fun `a name and vendor match still pairs, but says it was a guess`() {
        val pairs = DualBand.pair(
            listOf(
                radio("AA:BB:CC:11:11:11", "Home", 2437, -45.0),
                radio("AA:BB:CC:99:99:99", "Home", 5180, -52.0),
            ),
        )
        assertEquals(1, pairs.size)
        assertTrue(!pairs.first().sameHardware)
    }

    @Test
    fun `hardware matches are taken before name matches claim the radio`() {
        // The mesh node next door shares the network name. The box in this room shares its
        // own BSSID stem, and should win the 5 GHz radio that actually belongs to it.
        val pairs = DualBand.pair(
            listOf(
                radio("AA:BB:CC:DD:EE:01", "Home", 2437, -45.0),
                radio("AA:BB:CC:DD:EE:02", "Home", 5180, -60.0),
                radio("AA:BB:CC:99:99:02", "Home", 5200, -40.0),
            ),
        )
        val mine = pairs.first { it.low.bssid == "AA:BB:CC:DD:EE:01" }
        assertTrue(mine.sameHardware)
        assertEquals("AA:BB:CC:DD:EE:02", mine.high.bssid)
    }

    @Test
    fun `a single band access point is not paired with somebody else's radio`() {
        val pairs = DualBand.pair(
            listOf(
                radio("AA:BB:CC:DD:EE:01", "Home", 2437, -45.0),
                radio("11:22:33:44:55:66", "Neighbour", 5180, -52.0),
            ),
        )
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `a hidden network is not paired by name, because it has none`() {
        val pairs = DualBand.pair(
            listOf(
                radio("AA:BB:CC:11:11:11", null, 2437, -45.0),
                radio("AA:BB:CC:99:99:99", null, 5180, -52.0),
            ),
        )
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `one 5 GHz radio is not handed to two different access points`() {
        val pairs = DualBand.pair(
            listOf(
                radio("AA:BB:CC:11:11:11", "Home", 2437, -45.0),
                radio("AA:BB:CC:22:22:22", "Home", 2462, -50.0),
                radio("AA:BB:CC:33:33:33", "Home", 5180, -52.0),
            ),
        )
        assertEquals(1, pairs.size)
    }

    // ------------------------------------------------------------ the frequency term

    @Test
    fun `two co-located antennas differ before any wall is involved`() {
        val pair = DualBand.pair(
            listOf(
                radio("AA:BB:CC:DD:EE:01", "Home", 2437, -45.0),
                radio("AA:BB:CC:DD:EE:02", "Home", 5180, -45.0),
            ),
        ).first()
        // Twenty log of 5180 over 2437 is a little over six and a half decibels.
        assertEquals(6.55, pair.freeSpaceGapDb, 0.1)
    }

    @Test
    fun `the frequency term follows the actual channel, not a constant`() {
        val high = DualBand.pair(
            listOf(
                radio("AA:BB:CC:DD:EE:01", "Home", 2437, -45.0),
                radio("AA:BB:CC:DD:EE:02", "Home", 5825, -45.0),
            ),
        ).first()
        assertTrue("was ${high.freeSpaceGapDb}", high.freeSpaceGapDb > 7.5)
    }

    // ------------------------------------------------------- measuring against a place

    @Test
    fun `the transmit power difference cancels once there is a baseline`() {
        // An access point shouting 8 dB louder on 2.4 reads as an 8 dB gap in line of
        // sight. Behind a wall the gap is 20. The wall cost 12, not 20.
        val here = DualBand.pair(
            listOf(
                radio("AA:BB:CC:DD:EE:01", "Home", 2437, -50.0),
                radio("AA:BB:CC:DD:EE:02", "Home", 5180, -70.0),
            ),
        ).first()
        val penetration = Penetration(here, baselineGapDb = 8.0)
        assertEquals(12.0, penetration.excessDb!!, 0.001)
    }

    @Test
    fun `no baseline means no answer rather than a wrong one`() {
        val pair = DualBand.pair(
            listOf(
                radio("AA:BB:CC:DD:EE:01", "Home", 2437, -50.0),
                radio("AA:BB:CC:DD:EE:02", "Home", 5180, -70.0),
            ),
        ).first()
        val penetration = Penetration(pair, baselineGapDb = null)
        assertNull(penetration.excessDb)
        assertNull(penetration.verdict())
    }

    @Test
    fun `standing back where you started reports nothing in the way`() {
        val pair = DualBand.pair(
            listOf(
                radio("AA:BB:CC:DD:EE:01", "Home", 2437, -50.0),
                radio("AA:BB:CC:DD:EE:02", "Home", 5180, -57.0),
            ),
        ).first()
        val penetration = Penetration(pair, baselineGapDb = 7.0)
        assertTrue(penetration.verdict()!!.contains("Nothing much"))
    }

    @Test
    fun `a serious obstruction is called out as structural`() {
        val pair = DualBand.pair(
            listOf(
                radio("AA:BB:CC:DD:EE:01", "Home", 2437, -55.0),
                radio("AA:BB:CC:DD:EE:02", "Home", 5180, -73.0),
            ),
        ).first()
        val penetration = Penetration(pair, baselineGapDb = 7.0)
        assertNotNull(penetration.verdict())
        assertTrue(penetration.verdict()!!.contains("solid obstruction"))
    }

    @Test
    fun `a five gigahertz radio that has dropped out of usefulness says so first`() {
        val pair = DualBand.pair(
            listOf(
                radio("AA:BB:CC:DD:EE:01", "Home", 2437, -60.0),
                radio("AA:BB:CC:DD:EE:02", "Home", 5180, -84.0),
            ),
        ).first()
        val penetration = Penetration(pair, baselineGapDb = 7.0)
        assertTrue(!penetration.usable5)
        assertTrue(penetration.usable24)
        assertTrue(penetration.verdict()!!.contains("lost the argument"))
    }

    // ------------------------------------------------------------------ the arithmetic

    @Test
    fun `readings are averaged in decibels, not in power`() {
        // The dB mean of -50 and -70 is -60. The linear-power mean is about -53, which
        // would let one good reading hide a wall.
        assertEquals(-60.0, DualBand.mean(listOf(-50, -70))!!, 0.001)
    }

    @Test
    fun `an empty run of readings averages to nothing`() {
        assertNull(DualBand.mean(emptyList()))
    }

    @Test
    fun `the typical excess is a median, so one lift shaft cannot speak for a building`() {
        val pairs = listOf(4.0, 5.0, 6.0, 40.0).map {
            Penetration(
                DualBand.pair(
                    listOf(
                        radio("AA:BB:CC:DD:${it.toInt()}:01", "Home", 2437, -50.0),
                        radio("AA:BB:CC:DD:${it.toInt()}:02", "Home", 5180, -50.0 - it),
                    ),
                ).first(),
                baselineGapDb = 0.0,
            )
        }
        assertEquals(5.0, DualBand.typicalExcessDb(pairs)!!, 1.5)
    }

    @Test
    fun `a difference smaller than the scan noise is not a thinner wall`() {
        assertTrue(!DualBand.meaningful(2.0))
        assertTrue(!DualBand.meaningful(null))
        assertTrue(DualBand.meaningful(4.0))
        // Better on 5 GHz is just as real a finding as worse, and just as noisy.
        assertTrue(DualBand.meaningful(-4.0))
    }

    @Test
    fun `the description says which way round it went`() {
        assertTrue(DualBand.describe(9.0).contains("worse on 5 GHz"))
        assertTrue(DualBand.describe(-9.0).contains("better on 5 GHz"))
        assertEquals("same as the baseline", DualBand.describe(1.0))
        assertEquals("no baseline yet", DualBand.describe(null))
    }
}
