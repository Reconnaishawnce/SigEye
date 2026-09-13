package com.sigeye.core

import android.content.Context
import com.sigeye.core.analysis.presence.ConvoyTracker
import com.sigeye.core.analysis.presence.PlaceProfile
import com.sigeye.core.analysis.record.ForensicRecorder
import com.sigeye.core.analysis.record.TrackDetail
import com.sigeye.core.ble.Advert
import com.sigeye.core.ble.BeaconDecoder
import com.sigeye.core.ble.BleScanHub
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The long recordings, held where the screen cannot take them away.
 *
 * These used to live inside their screens, which meant the measurement belonged to the
 * composition: lock the phone or back out and a four-hour profile ended silently, wherever
 * it had got to. Holding the screen awake papered over that without fixing it.
 *
 * They are singletons now, driven by [ScanService] and merely *read* by the screens, so a
 * recording outlives whatever started it and a screen reopening simply reattaches to what
 * is already running.
 *
 * There is exactly one of each, which is a deliberate limit rather than an oversight -
 * two concurrent forensic recordings would share one radio and produce two half-pictures.
 */
object Recordings {

    val forensics = ForensicRecorder()
    val place = PlaceProfile()
    val convoy = ConvoyTracker()

    private var book: DeviceBook? = null

    /** The nickname book, for whoever else on the service tick needs one. */
    fun book(): DeviceBook? = book

    private var _follower: Follower? = null

    /**
     * Keeps names attached to devices that rotate their address.
     *
     * Unlike the recordings above it is never started or stopped: it listens to whatever
     * scanning is already happening, does nothing at all while no list has opted in, and
     * is read by a screen that shows what it has done.
     */
    val follower: Follower? get() = _follower

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pump: Job? = null

    fun init(context: Context) {
        val application = context.applicationContext
        if (book == null) book = DeviceBook.get(application)
        if (_follower == null) {
            _follower = Follower(DeviceBook.get(application), FollowStore.get(application))
        }
        startFollowing()
    }

    /**
     * A passenger on whatever is already scanning.
     *
     * The collector holds no claim on the radio, so subscribing for the life of the
     * process costs nothing: with nothing else scanning, no advertisements arrive and the
     * ticker finds nothing to do.
     */
    private fun startFollowing() {
        if (pump != null) return
        pump = scope.launch {
            launch {
                BleScanHub.adverts.collect { advert -> _follower?.onAdvert(advert) }
            }
            while (isActive) {
                delay(5_000)
                _follower?.tick(System.currentTimeMillis())
            }
        }
    }

    private fun labelFor(advert: Advert): String? =
        book?.nicknameOf(advert.address)
            ?: advert.name?.takeIf { it.isNotBlank() }
            ?: advert.vendor

    /** Feeds one advertisement to whichever recordings are running. */
    fun onAdvert(advert: Advert, modes: Set<ScanService.Mode>) {
        if (modes.contains(ScanService.Mode.FORENSICS)) {
            forensics.observe(
                address = advert.address,
                rssi = advert.rssi,
                atMs = advert.atMs,
                label = labelFor(advert),
                vendor = advert.vendor,
                isRandom = advert.isRandomAddress,
                detail = TrackDetail(
                    companyId = advert.companyId,
                    serviceUuids = advert.serviceUuids,
                    appearance = advert.appearance,
                    txPower = advert.txPower,
                    beaconProtocol = BeaconDecoder.decode(advert)?.protocol,
                    surveillanceNote = Vendors.surveillanceNote(
                        advert.address,
                        advert.companyId,
                        advert.name,
                    ),
                ),
            )
        }

        if (modes.contains(ScanService.Mode.PLACE)) {
            place.observe(
                address = advert.address,
                rssi = advert.rssi,
                atMs = advert.atMs,
                label = labelFor(advert),
            )
        }

        if (modes.contains(ScanService.Mode.CONVOY)) {
            convoy.observe(
                address = advert.address,
                rssi = advert.rssi,
                atMs = advert.atMs,
                label = labelFor(advert),
                vendor = advert.vendor,
                isRandom = advert.isRandomAddress,
            )
        }
    }

    /** One line per running recording, for the ongoing notification. */
    fun summary(modes: Set<ScanService.Mode>): List<String> = buildList {
        if (modes.contains(ScanService.Mode.FORENSICS)) {
            add(
                String.format(
                    Locale.US,
                    "Forensics: %d devices, %d packets",
                    forensics.deviceCount,
                    forensics.packetCount,
                ),
            )
        }
        if (modes.contains(ScanService.Mode.FOLLOW)) {
            add(FollowRunner.summary())
        }
        if (modes.contains(ScanService.Mode.PLACE)) {
            add("Place Profiler: ${place.deviceCount} devices")
        }
        if (modes.contains(ScanService.Mode.CONVOY)) {
            add("Journey: leg ${convoy.legCount}")
        }
    }
}
