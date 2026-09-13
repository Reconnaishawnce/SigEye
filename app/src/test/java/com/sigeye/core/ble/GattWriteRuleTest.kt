package com.sigeye.core.ble

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Bluetooth Explorer reads and never writes, and this is what holds that to it.
 *
 * A comment saying "do not add a write here" is advice. This is a build failure. The rule
 * is worth that much because the thing on the other end belongs to somebody else: reading
 * is asking a device to describe itself, which it was built to do for any stranger; writing
 * changes hardware belonging to a person who did not agree to it, and "it was only a test
 * device" is how that goes wrong.
 *
 * Scanning the source is a blunt instrument and it is the right one here. What is being
 * protected is a promise printed on the screen, and the promise is about which Android APIs
 * this app calls - so checking which Android APIs appear in the file is checking the actual
 * thing rather than a proxy for it.
 *
 * If a future version genuinely needs to write, this test is where to make that argument.
 * Deleting it silently also changes what the screen says, and the screen says there is a
 * test.
 */
class GattWriteRuleTest {

    /** Everything in the Android Bluetooth API that changes state on the other device. */
    private val forbidden = listOf(
        "writeCharacteristic",
        "writeDescriptor",
        "setCharacteristicNotification",
        "beginReliableWrite",
        "executeReliableWrite",
        "createBond",
        "removeBond",
        "setPin",
        "setPairingConfirmation",
    )

    private val guarded = listOf(
        "java/com/sigeye/core/ble/GattExplorer.kt",
        "java/com/sigeye/experiments/explorer/ExplorerScreen.kt",
    )

    /**
     * Finds a source file whether the tests run from the module or from the repository root.
     *
     * Gradle points a unit test at the module directory, but a test run any other way does
     * not, and a guard that quietly passes because it could not find the file is worse than
     * no guard.
     */
    private fun sourceFile(suffix: String): File =
        listOf("src/main/$suffix", "app/src/main/$suffix")
            .map { File(it) }
            .firstOrNull { it.exists() }
            ?: File("src/main/$suffix")

    @Test
    fun `nothing in the explorer writes, subscribes, pairs or bonds`() {
        guarded.forEach { path ->
            val file = sourceFile(path)
            assertTrue(
                "$path is missing (looked from ${File(".").absolutePath}). If the explorer " +
                    "moved, move this test with it - the promise it protects is printed on " +
                    "the screen.",
                file.exists(),
            )

            val source = file.readText()
            forbidden.forEach { call ->
                // The word appears in this file's own list and in prose about the rule, so
                // only a call - the name followed by an opening bracket - counts.
                if (source.contains("$call(")) {
                    fail(
                        "$path calls $call. Bluetooth Explorer promises on screen that it " +
                            "reads and never writes, subscribes, pairs or bonds. If that " +
                            "has to change, change the screen and the KDoc in " +
                            "GattExplorer.kt first, and then this test.",
                    )
                }
            }
        }
    }

    @Test
    fun `the promise is still written down where somebody extending it will read it`() {
        val source = sourceFile(guarded.first()).readText()

        assertTrue(
            "GattExplorer.kt no longer states the read-only rule in its KDoc.",
            source.contains("never writes a characteristic"),
        )
    }
}
