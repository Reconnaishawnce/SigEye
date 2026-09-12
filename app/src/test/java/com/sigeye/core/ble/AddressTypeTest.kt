package com.sigeye.core.ble

import com.sigeye.core.OuiRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AddressTypeTest {

    @Before
    fun setUp() = OuiRegistry.installForTest(File("src/main/assets/oui.bin").readBytes())

    @After
    fun tearDown() = OuiRegistry.installForTest(null)

    @Test
    fun `a registered prefix is public whatever its top bits say`() {
        // Espressif's block reads as a resolvable private address by bit pattern alone.
        assertEquals(AddressType.PUBLIC, AddressType.of("48:CA:43:BA:B2:C6"))
        assertTrue(!AddressType.of("48:CA:43:BA:B2:C6").isPrivacy)
    }

    @Test
    fun `top bits 11 is a static random address`() {
        // The one that matters most here: it looks like privacy and is not. It does not
        // change until the device restarts, so it can be followed indefinitely.
        val type = AddressType.of("C4:2A:6F:11:22:33")
        assertEquals(AddressType.RANDOM_STATIC, type)
        assertTrue(!type.isPrivacy)
        assertTrue(type.rotates.contains("until the device restarts"))
    }

    @Test
    fun `top bits 01 is a resolvable private address`() {
        val type = AddressType.of("4D:2A:6F:11:22:33")
        assertEquals(AddressType.RESOLVABLE_PRIVATE, type)
        assertTrue(type.isPrivacy)
    }

    @Test
    fun `top bits 00 is a non-resolvable private address`() {
        val type = AddressType.of("0D:2A:6F:11:22:33")
        assertEquals(AddressType.NON_RESOLVABLE_PRIVATE, type)
        assertTrue(type.isPrivacy)
    }

    @Test
    fun `nonsense does not throw`() {
        assertEquals(AddressType.UNKNOWN, AddressType.of("not-an-address"))
        assertEquals(AddressType.UNKNOWN, AddressType.of(""))
    }

    @Test
    fun `every type explains whether it rotates`() {
        AddressType.entries.forEach { assertTrue(it.rotates.isNotBlank()) }
    }
}

class PhyTest {
    @Test
    fun `physical layers are named`() {
        assertEquals("1M", Phy.label(Phy.LE_1M))
        assertEquals("2M", Phy.label(Phy.LE_2M))
        assertEquals("Coded", Phy.label(Phy.LE_CODED))
        assertEquals("none", Phy.label(0))
        assertTrue(Phy.label(9).contains("unknown"))
    }
}

class PayloadVarianceTest {

    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test
    fun `a payload that never changes is reported as identifying`() {
        // The finding this exists for: a device that rotates its address but leaves the
        // payload alone has handed over a better identifier than the one it discarded.
        val variance = PayloadVariance()
        repeat(10) { variance.observe(bytes(1, 2, 3, 4)) }

        assertEquals(4, variance.staticBytes)
        assertEquals(0, variance.varyingBytes)
        assertEquals("####", variance.mask())
        assertTrue(variance.leaksIdentity())
        assertTrue(variance.describe().contains("Nothing in the payload rotates"))
    }

    @Test
    fun `a payload that changes entirely gives nothing away`() {
        val variance = PayloadVariance()
        repeat(10) { index -> variance.observe(bytes(index, index + 1, index + 2, index + 3)) }

        assertEquals(0, variance.staticBytes)
        assertEquals("....", variance.mask())
        assertTrue(!variance.leaksIdentity())
        assertTrue(variance.describe().contains("gives nothing away"))
    }

    @Test
    fun `the mask shows exactly which bytes move`() {
        val variance = PayloadVariance()
        repeat(10) { index -> variance.observe(bytes(0x4C, 0x02, index, 0xFF)) }
        assertEquals("##.#", variance.mask())
        assertEquals(3, variance.staticBytes)
    }

    @Test
    fun `mostly static counts as leaking, mostly varying does not`() {
        val mostlyStatic = PayloadVariance()
        repeat(10) { index -> mostlyStatic.observe(bytes(1, 2, 3, index)) }
        assertTrue(mostlyStatic.leaksIdentity())

        val mostlyVarying = PayloadVariance()
        repeat(10) { index -> mostlyVarying.observe(bytes(1, index, index + 1, index + 2)) }
        assertTrue(!mostlyVarying.leaksIdentity())
    }

    @Test
    fun `too few packets says so rather than claiming everything is static`() {
        // One packet has nothing to differ from, so it always looks perfectly constant.
        val variance = PayloadVariance()
        variance.observe(bytes(1, 2, 3, 4))
        assertTrue(!variance.leaksIdentity())
        assertTrue(variance.describe().contains("Not enough packets"))
    }

    @Test
    fun `a change of length restarts the comparison`() {
        // Byte three of a twenty-five byte payload is not byte three of a four byte one.
        val variance = PayloadVariance()
        repeat(10) { variance.observe(bytes(1, 2, 3, 4)) }
        variance.observe(bytes(9, 9))
        assertEquals(2, variance.length)
        assertEquals(1, variance.samples)
    }

    @Test
    fun `no payload at all is handled`() {
        val variance = PayloadVariance()
        variance.observe(null)
        variance.observe(ByteArray(0))
        assertEquals(0, variance.length)
        assertTrue(variance.describe().contains("No manufacturer payload"))
    }

    @Test
    fun `resetting clears it`() {
        val variance = PayloadVariance()
        repeat(10) { variance.observe(bytes(1, 2, 3, 4)) }
        variance.reset()
        assertEquals(0, variance.length)
        assertEquals(0, variance.samples)
    }
}
