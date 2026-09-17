package com.sigeye.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import com.sigeye.core.analysis.covert.Blink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether this phone can transmit at all. */
sealed interface BlinkSupport {
    data object Ready : BlinkSupport

    /** The radio is off. */
    data object BluetoothOff : BlinkSupport

    /** Some phones receive but will not advertise. It is a chipset and firmware decision. */
    data object CannotAdvertise : BlinkSupport

    data object NoPermission : BlinkSupport
}

/** How far through a transmission it is. */
data class Blinking(
    val running: Boolean = false,
    val message: String = "",
    val slot: Int = 0,
    val slots: Int = 0,
    /** True while this slot is on the air, so a screen can show the light. */
    val lit: Boolean = false,
    val trouble: String? = null,
) {
    val progress: Float get() = if (slots <= 0) 0f else slot.toFloat() / slots
}

/**
 * Sending a message by switching the radio on and off.
 *
 * The payload never changes. Every packet this puts out is byte for byte identical, which
 * is the whole point: the message is in whether anything is being transmitted during each
 * slot of time, and not in anything a packet contains. Somebody capturing the traffic sees
 * one device saying the same nothing over and over.
 *
 * The one piece of content is a marker, so a receiver can tell which device to watch. That
 * is a necessary cheat and it is worth being plain about: the address rotates during a
 * message, so the sender cannot be recognized by address, and something in the packet has
 * to say "this one". Identity in the packet, meaning in the gaps.
 *
 * Company identifier 0xFFFF is used deliberately. The Bluetooth SIG reserves it for
 * internal and development use precisely so that nothing experimental has to squat on a
 * real manufacturer's number, which this would otherwise be doing.
 */
class Blinker(context: Context) {

    private val app = context.applicationContext

    private val manager: BluetoothManager? = runCatching {
        app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }.getOrNull()

    private val advertiser: BluetoothLeAdvertiser?
        get() = runCatching { manager?.adapter?.bluetoothLeAdvertiser }.getOrNull()

    private val _state = MutableStateFlow(Blinking())
    val state: StateFlow<Blinking> = _state

    private var callback: AdvertiseCallback? = null

    fun support(): BlinkSupport {
        val adapter = manager?.adapter ?: return BlinkSupport.CannotAdvertise
        if (!adapter.isEnabled) return BlinkSupport.BluetoothOff
        if (advertiser == null) return BlinkSupport.CannotAdvertise
        return BlinkSupport.Ready
    }

    /**
     * Blinks a message, one slot at a time, until it finishes or the caller gives up.
     *
     * A suspending function rather than a fire-and-forget service, so that leaving the
     * screen stops the radio. A phone quietly transmitting after somebody walked away from
     * the experiment is not a thing this app should be capable of.
     */
    @SuppressLint("MissingPermission")
    suspend fun transmit(message: String, slotMs: Long) {
        val slots = Blink.encode(message)
        _state.value = Blinking(
            running = true,
            message = Blink.clean(message),
            slot = 0,
            slots = slots.size,
        )

        try {
            slots.forEachIndexed { index, on ->
                if (on) start() else stop()
                _state.value = _state.value.copy(slot = index + 1, lit = on)
                delay(slotMs)
            }
        } finally {
            // Whatever happened, including cancellation, the radio stops.
            stop()
            _state.value = _state.value.copy(running = false, lit = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (callback != null) return
        val radio = advertiser ?: return

        val settings = AdvertiseSettings.Builder()
            // The fastest the platform offers, about one packet every hundred
            // milliseconds. Several packets per slot is what makes a lost one harmless.
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            // Nothing should be able to connect to this. It is a lamp, not a service.
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addManufacturerData(COMPANY, MARKER)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        val listener = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                callback = null
                _state.value = _state.value.copy(trouble = describe(errorCode))
            }
        }
        callback = listener
        runCatching { radio.startAdvertising(settings, data, listener) }
            .onFailure {
                callback = null
                _state.value = _state.value.copy(trouble = it.message)
            }
    }

    @SuppressLint("MissingPermission")
    private fun stop() {
        val listener = callback ?: return
        callback = null
        runCatching { advertiser?.stopAdvertising(listener) }
    }

    private fun describe(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "The packet was too large."
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
            "The radio is already advertising as much as it can. Something else on this " +
                "phone is using it."

        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Already advertising."
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "The Bluetooth stack failed."
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
            "This phone can receive Bluetooth but will not transmit it."

        else -> "Advertising failed, code $code."
    }

    companion object {
        /**
         * Reserved by the Bluetooth SIG for internal and development use.
         *
         * Anything experimental that picks a real company's identifier is putting that
         * company's name on its packets, and every scanner in range will believe it.
         */
        const val COMPANY = 0xFFFF

        /** Two bytes that say "this is the lamp", and carry nothing else. */
        val MARKER = byteArrayOf(0x53, 0x47)

        /** Whether an advertisement is one of ours. */
        fun isBlink(advert: Advert): Boolean =
            advert.companyId == COMPANY &&
                advert.manufacturerData?.let {
                    it.size >= MARKER.size && it[0] == MARKER[0] && it[1] == MARKER[1]
                } == true
    }
}
