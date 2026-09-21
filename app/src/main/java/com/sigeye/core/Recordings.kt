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

    private var _ownKit: OwnKitWatcher? = null

    /**
     * Keeps the "this is mine" mark attached to a device that rotates its address.
     *
     * Another passenger. Does nothing while the whitelist is empty, which is the state
     * most installs are in, so it costs nothing to have running.
     */
    val ownKit: OwnKitWatcher? get() = _ownKit

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
        if (mine == null) mine = MyDevices.get(application)
        if (_ownKit == null) _ownKit = OwnKitWatcher(MyDevices.get(application))
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
                BleScanHub.adverts.collect { advert ->
                    _follower?.onAdvert(advert)
                    _ownKit?.onAdvert(advert)
                }
            }
            while (isActive) {
                delay(5_000)
                val now = Clock.nowMs()
                _follower?.tick(now)
                _ownKit?.tick(now)
            }
        }
    }

    private fun labelFor(advert: Advert): String? =
        book?.nicknameOf(advert.address)
            ?: advert.name?.takeIf { it.isNotBlank() }
            ?: advert.vendor

    private var mine: MyDevices? = null

    /** Whether this packet came from something the user has said is theirs. */
    fun isMine(advert: Advert): Boolean = mine?.isMine(advert.address) == true

    /**
     * Whether a forensic recording includes the devices you said were yours.
     *
     * Off by default, like everywhere else. But a recording made to be gone through
     * afterwards is the one place where leaving something out is its own kind of lie, and
     * looking at your own phone is a reasonable thing to want to do - it is the device you
     * are allowed to experiment on. So this one is a switch on the screen rather than a
     * rule, and the screen says which way it is set.
     *
     * Deliberately not persisted. It is a decision about one recording.
     */
    @Volatile
    var forensicsIncludesMine: Boolean = false

    /** Feeds one advertisement to whichever recordings are running. */
    fun onAdvert(advert: Advert, modes: Set<ScanService.Mode>) {
        // Your own watch is not a stranger, and every count below is a count of strangers.
        // Forensics is the exception, and only when somebody has asked for it.
        val yours = isMine(advert)

        if (modes.contains(ScanService.Mode.FORENSICS) &&
            (!yours || forensicsIncludesMine)
        ) {
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

        if (yours) return

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
