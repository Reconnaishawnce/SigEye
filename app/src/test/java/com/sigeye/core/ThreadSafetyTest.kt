package com.sigeye.core

import com.sigeye.core.analysis.identity.AdvertShape
import com.sigeye.core.analysis.identity.RotationLab
import com.sigeye.core.analysis.presence.ConvoyTracker
import com.sigeye.core.analysis.presence.PlaceProfile
import com.sigeye.core.analysis.record.ForensicRecorder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything the scanning service writes while a screen reads it.
 *
 * [com.sigeye.core.ScanService] runs its pump and its tick on a background dispatcher, and
 * every one of these recorders lives in [Recordings] where a screen reads it on the main
 * thread. Iterating a map while another thread writes to it is a
 * ConcurrentModificationException, which is the app disappearing mid-recording - and it
 * took a follow dying twice on a street before I went looking for the rest of them.
 *
 * Each of these hammers one recorder from four threads at once. Before the guards they
 * throw within a second or two.
 */
class ThreadSafetyTest {

    private val t0 = 1_700_000_000_000L

    private val shape = AdvertShape(
        companyId = 0x004C,
        manufacturerLength = 25,
        manufacturerPrefix = "0f05",
        txPower = 12,
    )

    /** Two writers and two readers, and any exception from any of them fails the test. */
    private fun hammer(write: (Int) -> Unit, read: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        val failures = mutableListOf<Throwable>()

        fun run(work: (Int) -> Unit) {
            pool.submit {
                runCatching { repeat(600) { work(it) } }
                    .onFailure { synchronized(failures) { failures.add(it) } }
                done.countDown()
            }
        }

        repeat(2) { run(write) }
        repeat(2) { run(read) }

        assertTrue("workers did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertTrue("threw ${failures.firstOrNull()}", failures.isEmpty())
    }

    @Test
    fun `a place profile survives being read while it records`() {
        val profile = PlaceProfile()
        profile.start(t0)

        hammer(
            write = { profile.observe("AA:BB:CC:00:00:%02X".format(it % 80), -70, t0 + it * 50L) },
            read = { profile.report(t0 + it * 50L).residents.forEach { r -> r.label } },
        )
    }

    @Test
    fun `a forensic recording survives being read while it records`() {
        val recorder = ForensicRecorder()
        recorder.start(t0)

        hammer(
            write = {
                recorder.observe(
                    address = "AA:BB:CC:01:00:%02X".format(it % 80),
                    rssi = -70,
                    atMs = t0 + it * 50L,
                    isRandom = true,
                )
            },
            read = { recorder.tracks().forEach { track -> track.address } },
        )
    }

    @Test
    fun `a convoy tracker survives being read while it records`() {
        val tracker = ConvoyTracker()
        tracker.startLeg("here", t0)

        hammer(
            write = { tracker.observe("AA:BB:CC:02:00:%02X".format(it % 80), -70, t0 + it * 50L) },
            read = { tracker.report().candidates.forEach { c -> c.address } },
        )
    }

    @Test
    fun `a rotation lab survives being read while it records`() {
        val lab = RotationLab()

        hammer(
            write = {
                lab.observe(
                    address = "AA:BB:CC:03:00:%02X".format(it % 80),
                    rssi = -70,
                    atMs = t0 + it * 50L,
                    shape = shape,
                    isRandom = true,
                    payloadVendor = "Apple",
                    ouiVendor = null,
                )
            },
            read = { lab.tracks().forEach { track -> track.current } },
        )
    }

    /**
     * The structural half.
     *
     * A recorder added to [Recordings] later would be reached from both threads exactly
     * like these and nothing would say so until it crashed on somebody's walk, so the
     * rule is asserted against the source rather than left as a thing to remember.
     */
    @Test
    fun `every recorder the service feeds guards its own entry points`() {
        val sources = mapOf(
            "PlaceProfile" to "core/analysis/presence/PlaceProfile.kt",
            "ConvoyTracker" to "core/analysis/presence/Convoy.kt",
            "ForensicRecorder" to "core/analysis/record/Forensics.kt",
            "RotationLab" to "core/analysis/identity/RotationLab.kt",
            "FollowSession" to "core/analysis/identity/Follow.kt",
            "Follower" to "core/Follower.kt",
        )

        sources.forEach { (name, path) ->
            val text = File("src/main/java/com/sigeye/$path").readText()
            val body = text.substring(text.indexOf("class $name"))
            val functions = Regex("""^    fun [a-zA-Z]""", RegexOption.MULTILINE)
                .findAll(body).count()
            val guarded = Regex("""^    @Synchronized""", RegexOption.MULTILINE)
                .findAll(body).count()

            assertTrue("$name has no public entry points to guard", functions > 0)
            assertEquals(
                "$name has $functions entry points and $guarded guards. The scanning " +
                    "service writes to it from its own thread while a screen reads it.",
                functions,
                guarded,
            )
        }
    }
}
