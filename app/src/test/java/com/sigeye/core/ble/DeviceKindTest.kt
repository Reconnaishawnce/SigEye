package com.sigeye.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Working out what a device is from what it broadcasts.
 *
 * The tests that matter most are the ones about refusing to answer. A follow narrows to a
 * handful of devices and somebody then picks which person to follow, so a confident "Phone"
 * that was really a watch is a wrong answer delivered at the worst possible moment.
 */
class DeviceKindTest {

    private fun guess(
        appearance: Int? = null,
        companyId: Int? = null,
        continuity: Set<Int> = emptySet(),
        services: List<String> = emptyList(),
        name: String? = null,
    ) = DeviceKinds.of(appearance, companyId, continuity, services, name)

    // ------------------------------------------------------------------ declared

    @Test
    fun `a device that declares itself is taken at its word`() {
        // 0x00C0 is the watch category, subcategory zero.
        val watch = guess(appearance = 0x00C0)

        assertEquals(DeviceKind.WATCH, watch.kind)
        assertEquals(KindConfidence.DECLARED, watch.confidence)
        assertTrue(watch.certain)
    }

    @Test
    fun `the appearance beats everything else in the packet`() {
        // A name is whatever somebody typed. The appearance field is the specification's
        // own way of saying what a thing is.
        val phone = guess(appearance = 0x0040, name = "Kitchen Speaker")

        assertEquals(DeviceKind.PHONE, phone.kind)
    }

    @Test
    fun `earbuds declare themselves through either audio category`() {
        assertEquals(DeviceKind.EARBUDS, guess(appearance = 0x0280).kind)
        assertEquals(DeviceKind.EARBUDS, guess(appearance = 0x0281).kind)
    }

    // ------------------------------------------------------------------ inferred from Apple

    @Test
    fun `proximity pairing means headphones`() {
        val pods = guess(companyId = Continuity.APPLE, continuity = setOf(0x07))

        assertEquals(DeviceKind.EARBUDS, pods.kind)
        assertEquals(KindConfidence.INFERRED, pods.confidence)
    }

    @Test
    fun `find my on its own means something separated from its owner`() {
        val tag = guess(companyId = Continuity.APPLE, continuity = setOf(0x12))

        assertEquals(DeviceKind.TAG, tag.kind)
    }

    @Test
    fun `find my alongside nearby info is a device in use, not a tag`() {
        // A phone in Find My mode still talks like a phone. Calling it a tag would drop a
        // real target out of a filter at the moment it mattered.
        val phone = guess(companyId = Continuity.APPLE, continuity = setOf(0x12, 0x10))

        assertEquals(DeviceKind.APPLE, phone.kind)
    }

    @Test
    fun `an airplay target is a speaker rather than something on a person`() {
        val tv = guess(companyId = Continuity.APPLE, continuity = setOf(0x09))

        assertEquals(DeviceKind.AUDIO, tv.kind)
    }

    // ------------------------------------------------------------------ refusing to answer

    @Test
    fun `an iphone and an apple watch are not told apart, and it says so`() {
        // This is the honest part. Both send Nearby Info, both under company 0x004C, both
        // with the body randomized, and from one advertisement there is very often nothing
        // separating them. Calling it a phone would invent the one fact somebody is trying
        // to establish.
        val inUse = guess(companyId = Continuity.APPLE, continuity = setOf(0x10, 0x0C))

        assertEquals(DeviceKind.APPLE, inUse.kind)
        assertTrue(!inUse.certain)
        assertTrue(inUse.because.contains("does not say which"))
    }

    @Test
    fun `an apple device saying nothing distinctive is still only called Apple`() {
        assertEquals(
            DeviceKind.APPLE,
            guess(companyId = Continuity.APPLE, continuity = setOf(0x10)).kind,
        )
        assertEquals(DeviceKind.APPLE, guess(companyId = Continuity.APPLE).kind)
    }

    @Test
    fun `a packet with nothing in it is unknown rather than guessed at`() {
        val nothing = guess()

        assertEquals(DeviceKind.UNKNOWN, nothing.kind)
        assertEquals(KindConfidence.GUESSED, nothing.confidence)
    }

    // ------------------------------------------------------------------ services and names

    @Test
    fun `the find my service uuid marks a tag`() {
        val tag = guess(services = listOf("0000fd44-0000-1000-8000-00805f9b34fb"))

        assertEquals(DeviceKind.TAG, tag.kind)
    }

    @Test
    fun `a name is used last and flagged as the weakest thing in the packet`() {
        val named = guess(name = "Shawn's AirPods Pro")

        assertEquals(DeviceKind.EARBUDS, named.kind)
        assertEquals(KindConfidence.GUESSED, named.confidence)
        assertTrue(named.because.contains("weakest"))
    }

    @Test
    fun `a name is not consulted when the packet says something firmer`() {
        // A phone called "Car" is a phone. Plenty of devices carry the name of something
        // they are not.
        val pods = guess(
            companyId = Continuity.APPLE,
            continuity = setOf(0x07),
            name = "Living Room TV",
        )

        assertEquals(DeviceKind.EARBUDS, pods.kind)
    }

    @Test
    fun `every filterable kind is one this can actually produce`() {
        // A picker offering a category nothing is ever classified as is a dead end that
        // looks like a bug in the radio.
        assertTrue(DeviceKind.UNKNOWN !in DeviceKind.FILTERABLE)
        assertTrue(DeviceKind.PHONE in DeviceKind.FILTERABLE)
        assertTrue(DeviceKind.APPLE in DeviceKind.FILTERABLE)
    }
}
