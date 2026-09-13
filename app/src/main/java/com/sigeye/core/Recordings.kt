package com.sigeye.core

import android.content.Context
import com.sigeye.core.analysis.identity.RotationLab
import com.sigeye.core.analysis.presence.ConvoyTracker
import com.sigeye.core.analysis.presence.PlaceProfile
import com.sigeye.core.analysis.record.ForensicRecorder
import com.sigeye.core.analysis.record.TrackDetail
import com.sigeye.core.ble.Advert
import com.sigeye.core.ble.BeaconDecoder
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.ble.shape
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

    /**
     * A whole room's address privacy, measured over however long you leave it.
     *
     * Out here with the other long recordings because its best output needs half an hour of
     * a busy carriage, and a measurement that lives in a composition is a measurement that
     * ends when the screen locks. This is also the instrument the rotation paper depends
     * on: ninety minutes of ten phones is the experiment, and it cannot be ninety minutes
     * of holding a lit screen.
     */
    var rotationLab = RotationLab()
        private set

    /** When the rotation lab started, so it can say how long it has been listening. */
    var rotationLabStartedAtMs: Long = 0L
        private set

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

        if (modes.contains(ScanService.Mode.ROTATION_LAB)) {
            rotationLab.observe(
                address = advert.address,
                rssi = advert.rssi,
                atMs = advert.atMs,
                shape = advert.shape(),
                isRandom = advert.isRandomAddress,
                payloadVendor = advert.companyId?.let { Vendors.byCompanyId(it) },
                ouiVendor = Vendors.byAddress(advert.address),
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

    /**
     * Starts a fresh measurement of the room.
     *
     * A new instance rather than a reset, because the lab has a good deal of state - every
     * address, every gap between changes, every shape - and "clear all of it" is the same
     * thing as a new one with one more place to forget a field.
     */
    fun startRotationLab(nowMs: Long) {
        rotationLab = RotationLab()
        rotationLabStartedAtMs = nowMs
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
        if (modes.contains(ScanService.Mode.ROTATION_LAB)) {
            add("Rotation Lab: ${rotationLab.addressCount} addresses")
        }
        if (modes.contains(ScanService.Mode.PLACE)) {
            add("Place Profiler: ${place.deviceCount} devices")
        }
        if (modes.contains(ScanService.Mode.CONVOY)) {
            add("Journey: leg ${convoy.legCount}")
        }
    }
}
