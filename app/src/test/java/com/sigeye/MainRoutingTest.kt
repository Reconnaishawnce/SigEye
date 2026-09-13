package com.sigeye

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pinned target has to be reachable from every screen, and the routes it offers have to
 * go somewhere.
 *
 * All of this is composition wiring, which no unit test can run and which fails silently:
 * the bar simply does not appear, or a route opens nothing, and neither throws. Reading the
 * source is the only way to hold it, and it is the same trick GattWriteRuleTest uses.
 */
class MainRoutingTest {

    private val main = File("src/main/java/com/sigeye/MainActivity.kt").readText()

    @Test
    fun `the bar is mounted once, above whatever screen is showing`() {
        assertTrue("MainActivity.kt not where this test expected it", main.isNotEmpty())

        assertTrue(
            "TargetBar is not mounted in MainActivity. Putting it on each experiment " +
                "instead would be twenty-eight places to forget it.",
            main.contains("TargetBar("),
        )
        assertTrue(
            "The bar must sit above the screen rather than inside it, or it disappears on " +
                "every screen that returns early",
            main.indexOf("TargetBar(") < main.indexOf("private fun Screen("),
        )
    }

    @Test
    fun `every route the bar offers has somewhere to go`() {
        // To the end of the mount rather than to the first bracket, which closes inside
        // the first route rather than after the last one.
        val routes = main.substringAfter("TargetRoutes(").substringBefore("Screen(stack")

        listOf("onLocate", "onRadar", "onRotation").forEach { route ->
            assertTrue("$route is not wired", routes.contains(route))
        }
        listOf("LOCATE_PREFIX", "RADAR_PREFIX", "ROTATION_PREFIX").forEach { prefix ->
            assertTrue(
                "$prefix is offered by the bar but nothing routes it",
                main.contains("startsWith($prefix)"),
            )
        }
    }

    @Test
    fun `a pinned device can be pinned from anywhere that shows one`() {
        // The shared action sheet is what the device screens all use, so putting it there
        // once reaches every one of them.
        val actions = File("src/main/java/com/sigeye/ui/DeviceActions.kt").readText()

        assertTrue(actions.contains("CurrentTarget"))
        assertTrue(
            "pinning should be offered before the nickname field, because it is what " +
                "somebody who has just found a device actually wants",
            actions.indexOf("current.pin(") < actions.indexOf("label = { Text(\"Nickname\") }"),
        )
    }

    @Test
    fun `a rotation moves the pin as well as the target`() {
        // Kept in step where rotations are decided rather than at every call site. A screen
        // that moved one and forgot the other would leave the bar pointing at an address
        // nothing is ever going to say again.
        val targets = File("src/main/java/com/sigeye/core/Targets.kt").readText()
        val reacquire = targets.substringAfter("fun reacquire(").substringBefore("\n    }")

        assertTrue(
            "TargetStore.reacquire no longer carries the pin across",
            reacquire.contains("CurrentTarget") && reacquire.contains("reacquire("),
        )
    }
}
