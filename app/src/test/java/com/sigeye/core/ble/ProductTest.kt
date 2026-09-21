package com.sigeye.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Naming the thing rather than its category.
 *
 * A list of forty devices in somebody's own house is useless if thirty of them say Unknown
 * and the rest say Apple device. Most of them can be named outright, and the ones that
 * genuinely cannot should say so rather than pick the likelier answer.
 */
class ProductTest {

    private fun hex(text: String) = ByteArray(text.length / 2) {
        text.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private fun advert(
        address: String = "AA:BB:CC:DD:EE:FF",
        name: String? = null,
        companyId: Int? = null,
        manufacturerData: ByteArray? = null,
        serviceUuids: List<String> = emptyList(),
        serviceData: Map<String, ByteArray> = emptyMap(),
        txPower: Int? = null,
    ) = Advert(
        address = address,
        rssi = -60,
        atMs = 1_700_000_000_000L,
        name = name,
        companyId = companyId,
        manufacturerData = manufacturerData,
        serviceUuids = serviceUuids,
        serviceData = serviceData,
        txPower = txPower,
    )

    /** One Apple Continuity message, wrapped the way the payload carries it. */
    private fun continuity(vararg messages: Pair<Int, String>): ByteArray {
        val out = mutableListOf<Byte>()
        messages.forEach { (type, body) ->
            val bytes = hex(body)
            out.add(type.toByte())
            out.add(bytes.size.toByte())
            out.addAll(bytes.toList())
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ Google

    @Test
    fun `a Chromecast is named, because only Cast devices send that service`() {
        // The specific thing asked for: telling a Chromecast from a Pixel. It is not a
        // guess, it is a service the SIG assigned to Google for exactly this.
        val product = Products.of(advert(serviceUuids = listOf("FEA0")))

        assertEquals("Chromecast or Google speaker", product.name)
        assertEquals(KindConfidence.DECLARED, product.confidence)
        assertEquals(DeviceKind.AUDIO, product.kind)
    }

    @Test
    fun `a phone advertising Google's nearby service is not called a Chromecast`() {
        val product = Products.of(advert(serviceUuids = listOf("FEF3")))

        assertEquals(DeviceKind.PHONE, product.kind)
        assertTrue(product.name.contains("Android"))
    }

    @Test
    fun `a Fast Pair accessory in pairing mode gives up its model number`() {
        val product = Products.of(
            advert(serviceUuids = listOf("FE2C"), serviceData = mapOf("FE2C" to hex("2CB81B"))),
        )

        assertTrue(product.name.contains("waiting to pair"))
        assertTrue(product.details.any { it.first == "Fast Pair model" && it.second == "0x2CB81B" })
    }

    @Test
    fun `a Fast Pair accessory that is already paired has no model to give`() {
        // The longer payload is an account key filter, which says nothing about the model.
        // Reading it as one would print a number that means nothing.
        val product = Products.of(
            advert(
                serviceUuids = listOf("FE2C"),
                serviceData = mapOf("FE2C" to hex("00112233445566")),
            ),
        )

        assertTrue(product.name.contains("already paired"))
        assertTrue(product.details.none { it.first == "Fast Pair model" })
    }

    @Test
    fun `a full length UUID is recognized as the short one it stands for`() {
        // Android hands back the 128-bit form, and a matcher that only knows the short
        // form silently recognizes nothing at all.
        val long = "0000fea0-0000-1000-8000-00805f9b34fb"

        assertEquals(0xFEA0, Products.shortUuid(long))
        assertEquals("Chromecast or Google speaker", Products.of(advert(serviceUuids = listOf(long))).name)
    }

    @Test
    fun `a genuine 128 bit UUID is not mistaken for a short one`() {
        assertNull(Products.shortUuid("d0611e78-bbb4-4591-a5f8-487910ae4366"))
    }

    // ------------------------------------------------------------------ Apple

    @Test
    fun `AirPods are named and their model number is shown rather than guessed at`() {
        val product = Products.of(
            advert(companyId = 0x004C, manufacturerData = continuity(0x07 to "01200E55AABB")),
        )

        assertEquals("AirPods or Beats", product.name)
        assertEquals(DeviceKind.EARBUDS, product.kind)
        assertTrue(product.details.any { it.first == "Model number" })
    }

    @Test
    fun `an AirTag is told apart from a phone relaying Find My`() {
        // A phone in the Find My network sends Nearby Info alongside it. A tag has nothing
        // else to send, and that absence is the whole discriminator.
        val tag = Products.of(
            advert(companyId = 0x004C, manufacturerData = continuity(0x12 to "00112233")),
        )
        val phone = Products.of(
            advert(
                companyId = 0x004C,
                manufacturerData = continuity(0x12 to "00112233", 0x10 to "050A1122"),
            ),
        )

        assertEquals(DeviceKind.TAG, tag.kind)
        assertTrue(tag.name.contains("AirTag"))
        assertTrue(phone.kind != DeviceKind.TAG)
    }

    @Test
    fun `personal hotspot means a phone, because a watch does not offer one`() {
        val product = Products.of(
            advert(
                companyId = 0x004C,
                manufacturerData = continuity(0x0E to "0102", 0x10 to "050A1122"),
            ),
        )

        assertEquals(DeviceKind.PHONE, product.kind)
        assertTrue(product.name.contains("iPhone"))
    }

    @Test
    fun `a phone and a watch that look alike are not separated by guessing`() {
        // The honest case, and the one worth protecting. Apple sends the same messages from
        // both with the identifying parts randomized. Calling this a phone would be
        // inventing a fact at the moment somebody is deciding which person to follow.
        val product = Products.of(
            advert(companyId = 0x004C, manufacturerData = continuity(0x10 to "050A1122")),
        )

        assertEquals(DeviceKind.APPLE, product.kind)
        assertTrue(product.name.contains("unclear"))
        assertTrue(product.because.contains("Watch"))
    }

    @Test
    fun `an Apple payload whose lengths do not add up is not read at all`() {
        // A misread payload would put invented structure into the answer.
        val product = Products.of(
            advert(companyId = 0x004C, manufacturerData = hex("07FF0102")),
        )

        assertTrue(product.name != "AirPods or Beats")
    }

    // ------------------------------------------------------------------ Microsoft

    @Test
    fun `a Windows laptop is reported as saying so, because it is self-reported`() {
        val product = Products.of(advert(companyId = 0x0006, manufacturerData = hex("010F1122")))

        assertTrue(product.name.contains("Windows laptop"))
        assertTrue(product.because.contains("chose what to put there"))
    }

    @Test
    fun `an unlisted Microsoft device type is shown as a number, not invented`() {
        val product = Products.of(advert(companyId = 0x0006, manufacturerData = hex("011E1122")))

        assertTrue(product.details.any { it.second.contains("type 30") })
    }

    // ------------------------------------------------------------------ the rest

    @Test
    fun `a Tile is named from the service the SIG assigned to Tile`() {
        assertEquals(DeviceKind.TAG, Products.of(advert(serviceUuids = listOf("FEED"))).kind)
    }

    @Test
    fun `something nobody can name falls back to the category and says why`() {
        val product = Products.of(advert(address = "5A:11:22:33:44:55"))

        assertTrue(product.because.isNotBlank())
        assertEquals(DeviceKind.UNKNOWN, product.kind)
    }

    @Test
    fun `the common details are captured whoever made the thing`() {
        val details = Products.common(
            advert(name = "Kitchen speaker", txPower = -12, serviceUuids = listOf("180F")),
        )

        assertTrue(details.any { it.first == "Broadcast name" && it.second == "Kitchen speaker" })
        assertTrue(details.any { it.first == "Transmit power" && it.second == "-12 dBm" })
        assertTrue(details.any { it.first == "Services" && it.second.contains("Battery") })
    }
}
