package com.sigeye.core.analysis.surveillance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adding up what a drive found, and not counting one camera as fifty.
 *
 * The serial is what makes this work. A Flock battery broadcasts the number printed on its
 * case and rotates its Bluetooth address, so the address is useless across days and the
 * serial is not.
 */
class SweepTest {

    private val t0 = 1_700_000_000_000L

    private val withSerial = Sighting(
        product = "Flock Safety camera battery",
        operator = "Flock Safety",
        why = "Broadcasts its own serial number, TN72023022000771",
        certainty = Certainty.CONFIRMED,
        serial = "TN72023022000771",
    )

    private val noSerial = Sighting(
        product = "XUNTONG device",
        operator = "unknown",
        why = "Manufacturer data from XUNTONG (0x09C8)",
        certainty = Certainty.POSSIBLE,
    )

    private fun fold(
        existing: Found?,
        sighting: Sighting = withSerial,
        address: String = "D8:A0:D8:9F:4A:5E",
        rssi: Int = -70,
        atMs: Long = t0,
        lat: Double? = 44.98,
        lon: Double? = -93.27,
        accuracy: Float? = 8f,
    ) = Sweep.fold(existing, sighting, address, rssi, atMs, lat, lon, accuracy)

    // ------------------------------------------------------------------ identity

    @Test
    fun `a camera with a serial is filed under it, not under its address`() {
        assertEquals("TN72023022000771", Sweep.keyFor(withSerial, "D8:A0:D8:9F:4A:5E"))
    }

    @Test
    fun `the same camera under a new address updates the same row`() {
        // The whole reason a sweep can add up over weeks. Its address rotates; the number
        // printed on the case does not.
        val monday = fold(null, rssi = -80, atMs = t0)
        val friday = fold(
            monday,
            address = "5C:11:22:33:44:55",
            rssi = -60,
            atMs = t0 + 4 * 86_400_000L,
        )

        assertEquals(monday.key, friday.key)
        assertEquals(2, friday.sightings)
    }

    @Test
    fun `something with no serial is filed under its address and says it will duplicate`() {
        val one = fold(null, sighting = noSerial, address = "AA:BB:CC:DD:EE:FF")

        assertEquals("AA:BB:CC:DD:EE:FF", one.key)
        assertTrue(!one.durable)
        assertNull(one.serial)
    }

    @Test
    fun `an address key is matched however it was punctuated`() {
        assertEquals("AA:BB:CC:DD:EE:FF", Sweep.keyFor(noSerial, "aa:bb:cc:dd:ee:ff"))
    }

    // ------------------------------------------------------------------ where it is

    @Test
    fun `the position kept is where you heard it loudest`() {
        // That is the closest you got to it, which is a better guess at where the thing is
        // than wherever you happened to be when you last heard it faintly.
        val far = fold(null, rssi = -90, lat = 44.0, lon = -93.0)
        val close = fold(far, rssi = -55, lat = 44.98, lon = -93.27)
        val farAgain = fold(close, rssi = -92, lat = 45.5, lon = -94.0)

        assertEquals(44.98, farAgain.latitude!!, 0.0001)
        assertEquals(-93.27, farAgain.longitude!!, 0.0001)
        assertEquals(-55, farAgain.bestRssi)
    }

    @Test
    fun `a reading with no location does not erase one that had it`() {
        val placed = fold(null, rssi = -80)
        val nowhere = fold(placed, rssi = -50, lat = null, lon = null, accuracy = null)

        assertTrue(nowhere.hasPlace)
        assertEquals(44.98, nowhere.latitude!!, 0.0001)
    }

    // ------------------------------------------------------------------ getting surer

    @Test
    fun `a stronger claim arriving later replaces a weaker one`() {
        // The name arrives in one frame and the serial in another, so a possible becomes a
        // confirmed as the packets land.
        val first = fold(null, sighting = noSerial, address = "D8:A0:D8:9F:4A:5E")
        val better = Sweep.fold(
            existing = first.copy(key = "TN72023022000771"),
            sighting = withSerial,
            address = "D8:A0:D8:9F:4A:5E",
            rssi = -70,
            atMs = t0,
            latitude = null,
            longitude = null,
            accuracyM = null,
        )

        assertEquals(Certainty.CONFIRMED, better.certainty)
        assertEquals("TN72023022000771", better.serial)
        assertTrue(better.product.contains("Flock"))
    }

    @Test
    fun `a weaker later reading does not downgrade a confirmed find`() {
        val confirmed = fold(null)
        val vaguer = fold(confirmed, sighting = noSerial)

        assertEquals(Certainty.CONFIRMED, vaguer.certainty)
        assertTrue(vaguer.product.contains("Flock"))
    }

    @Test
    fun `first seen stays put and last seen moves`() {
        val first = fold(null, atMs = t0)
        val later = fold(first, atMs = t0 + 60_000L)

        assertEquals(t0, later.firstSeenMs)
        assertEquals(t0 + 60_000L, later.lastSeenMs)
    }

    // ------------------------------------------------------------------ the output

    @Test
    fun `confirmed finds come first, then whichever you got closest to`() {
        val sure = fold(null, rssi = -80)
        val maybe = fold(null, sighting = noSerial, address = "AA:BB:CC:DD:EE:FF", rssi = -40)

        assertEquals(listOf(sure.key, maybe.key), Sweep.ordered(listOf(maybe, sure)).map { it.key })
    }

    @Test
    fun `the export has a header and one row per find`() {
        val rows = Sweep.csv(listOf(fold(null), fold(null, sighting = noSerial, address = "AA:BB:CC:DD:EE:FF")))
            .trim()
            .lines()

        assertEquals(3, rows.size)
        assertEquals(Sweep.HEADER, rows.first())
        assertTrue(rows[1].contains("TN72023022000771"))
    }

    @Test
    fun `a comma inside a field cannot break the spreadsheet`() {
        val awkward = withSerial.copy(product = "Camera, pole mounted")
        val row = Sweep.csv(listOf(fold(null, sighting = awkward))).trim().lines()[1]

        assertEquals(Sweep.HEADER.split(",").size, row.split(",").size)
    }

    @Test
    fun `the summary counts what was found and what has a place`() {
        val placed = fold(null)
        val unplaced = fold(null, sighting = noSerial, address = "AA:BB:CC:DD:EE:FF", lat = null, lon = null)

        val line = Sweep.summarize(listOf(placed, unplaced))

        assertTrue(line.contains("2 found"))
        assertTrue(line.contains("1 confirmed"))
        assertTrue(line.contains("1 with a location"))
    }

    @Test
    fun `an empty sweep says so plainly`() {
        assertEquals("Nothing found yet.", Sweep.summarize(emptyList()))
    }
}
