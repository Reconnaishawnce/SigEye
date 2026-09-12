package com.sigeye.core

import android.content.Context
import com.sigeye.core.analysis.ConvoyTracker
import com.sigeye.core.analysis.ForensicRecorder
import com.sigeye.core.analysis.PlaceProfile
import com.sigeye.core.analysis.TrackDetail
import com.sigeye.core.ble.Advert
import com.sigeye.core.ble.BeaconDecoder
import java.util.Locale

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

    fun init(context: Context) {
        if (book == null) book = DeviceBook.get(context.applicationContext)
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
        if (modes.contains(ScanService.Mode.PLACE)) {
            add("Place Profiler: ${place.deviceCount} devices")
        }
        if (modes.contains(ScanService.Mode.CONVOY)) {
            add("Journey: leg ${convoy.legCount}")
        }
    }
}
