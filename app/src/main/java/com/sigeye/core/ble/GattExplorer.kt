package com.sigeye.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where a connection has got to. */
enum class ExploreState(val label: String) {
    IDLE("Not connected"),
    CONNECTING("Connecting"),
    DISCOVERING("Asking what it has"),
    READING("Reading values"),
    DONE("Finished"),
    FAILED("Could not connect"),
}

/** One line of the running commentary. */
data class ExploreStep(val atMs: Long, val text: String, val detail: String? = null)

data class GattValue(
    val uuid: String,
    val name: String,
    val properties: Int,
    val value: String? = null,
    val failed: Boolean = false,
) {
    val readable: Boolean get() = GattGlossary.isReadable(properties)
    val explanation: String get() = GattGlossary.explainCharacteristic(uuid)
    val propertyWords: String get() = GattGlossary.describeProperties(properties)
}

data class GattService(
    val uuid: String,
    val name: String,
    val explanation: String,
    val characteristics: List<GattValue>,
)

data class Exploration(
    val state: ExploreState = ExploreState.IDLE,
    val address: String? = null,
    val services: List<GattService> = emptyList(),
    val steps: List<ExploreStep> = emptyList(),
    val error: String? = null,
) {
    val readCount: Int get() = services.sumOf { service -> service.characteristics.count { it.value != null } }
    val characteristicCount: Int get() = services.sumOf { it.characteristics.size }
}

/**
 * Connects to one device and asks it what it has.
 *
 * This is the only thing in the app that transmits. Everything else listens to
 * advertisements that were going to be broadcast anyway; this opens a connection, which
 * the other device sees, may log, and on some hardware will show as a pairing prompt to
 * whoever is holding it. That is a real difference in kind, not degree, so the screen that
 * drives this asks first.
 *
 * ## The rule: it reads, and that is all
 *
 * **This class never writes a characteristic, never writes a descriptor, never subscribes
 * to a notification, and never pairs or bonds.** It connects, asks for the service list,
 * reads the values the device has already marked readable, and disconnects.
 *
 * That is not an accident of what has been implemented so far, it is the boundary, and it
 * is written down here because the next person to extend this file will have a good reason
 * to cross it. Reading is asking a device to describe itself, which it was built to do for
 * any stranger that asks. Writing is changing something on hardware belonging to somebody
 * who did not agree to it - a light, a lock, a pump - and "it was only a test device" is
 * exactly how that goes wrong. Subscribing leaves a connection open and a device talking to
 * you after you stopped looking. Bonding leaves a lasting relationship on somebody else's
 * property.
 *
 * `GattWriteRuleTest` reads this source file and fails the build if a mutating call appears
 * in it. If you have a real reason to cross the line, the test is where to argue it, and
 * the screen has to say so too.
 *
 * GATT is strictly one operation at a time. Issuing a second read before the first
 * callback returns does not queue it - it silently fails, and the usual symptom is a device
 * that appears to have three readable values when it has thirty. So reads run from a queue,
 * one per callback, and a watchdog gives up rather than hanging forever on a device that
 * accepted the connection and then stopped answering.
 */
class GattExplorer(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(Exploration())
    val state: StateFlow<Exploration> = _state

    private var gatt: BluetoothGatt? = null
    private val pending = ArrayDeque<BluetoothGattCharacteristic>()
    private var startedAtMs = 0L
    private var finished = false

    private val timeout = Runnable {
        if (_state.value.state !in setOf(ExploreState.DONE, ExploreState.FAILED)) {
            step("Gave up waiting.", "The device accepted a connection but stopped " +
                "answering. That is common: plenty of things advertise but will not talk " +
                "to a stranger.")
            finish(ExploreState.DONE)
        }
    }

    @SuppressLint("MissingPermission")
    fun explore(address: String) {
        close()
        finished = false
        startedAtMs = System.currentTimeMillis()
        _state.value = Exploration(state = ExploreState.CONNECTING, address = address)

        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            fail("Bluetooth is off.")
            return
        }

        val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(address) }
            .getOrNull() ?: run {
            fail("That address is not one this phone can dial.")
            return
        }

        step(
            "Opening a connection to $address.",
            "This is the first moment the app transmits. Until now it has only listened.",
        )

        gatt = runCatching {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()

        if (gatt == null) {
            fail("The phone refused to open a connection.")
            return
        }
        handler.postDelayed(timeout, OVERALL_TIMEOUT_MS)
    }

    /** Back to the picker, forgetting the last exploration. */
    fun reset() {
        _state.value = Exploration()
    }

    @SuppressLint("MissingPermission")
    fun close() {
        handler.removeCallbacks(timeout)
        pending.clear()
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    step(
                        "Connected.",
                        "The device agreed to talk. Now asking for its list of services - " +
                            "this is called service discovery, and it is the device " +
                            "describing its own layout.",
                    )
                    update { it.copy(state = ExploreState.DISCOVERING) }
                    runCatching { gatt.discoverServices() }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    if (_state.value.state == ExploreState.CONNECTING) {
                        // 133 is Android's catch-all GATT error and usually means the
                        // device simply refused, rather than anything being wrong here.
                        fail(
                            if (status == GATT_ERROR) {
                                "The device refused the connection. Most things that " +
                                    "advertise are not connectable at all - beacons and " +
                                    "tags broadcast one way and never accept callers."
                            } else {
                                "Disconnected before it said anything (status $status)."
                            },
                        )
                    } else if (!finished) {
                        step("The device hung up.")
                        finish(ExploreState.DONE)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Service discovery failed (status $status).")
                return
            }
            val services = gatt.services.orEmpty()
            step(
                "Found ${services.size} service${if (services.size == 1) "" else "s"}.",
                "A service is a group of related values. Standard ones are in the " +
                    "Bluetooth registry; custom ones were invented by the maker.",
            )

            val model = services.map { service ->
                val uuid = service.uuid.toString()
                GattService(
                    uuid = uuid,
                    name = GattGlossary.serviceName(uuid) ?: "Custom service",
                    explanation = GattGlossary.explainService(uuid),
                    characteristics = service.characteristics.orEmpty().map { characteristic ->
                        val id = characteristic.uuid.toString()
                        GattValue(
                            uuid = id,
                            name = GattGlossary.characteristicName(id) ?: "Custom value",
                            properties = characteristic.properties,
                        )
                    },
                )
            }
            update { it.copy(services = model, state = ExploreState.READING) }

            pending.clear()
            services.forEach { service ->
                service.characteristics.orEmpty().forEach { characteristic ->
                    if (GattGlossary.isReadable(characteristic.properties)) {
                        pending.add(characteristic)
                    }
                }
            }
            step(
                "${pending.size} of ${model.sumOf { it.characteristics.size }} values can " +
                    "be read without pairing.",
                "The rest are write-only, notify-only, or need a bonded pairing first.",
            )
            readNext()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = handleRead(characteristic.uuid.toString(), value, status)

        @Deprecated("Needed for API levels below 33, which is most of them.")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) = handleRead(
            characteristic.uuid.toString(),
            characteristic.value ?: ByteArray(0),
            status,
        )
    }

    private fun handleRead(uuid: String, bytes: ByteArray, status: Int) {
        val ok = status == BluetoothGatt.GATT_SUCCESS
        val rendered = if (ok) GattGlossary.render(uuid, bytes) else null
        update { current ->
            current.copy(
                services = current.services.map { service ->
                    service.copy(
                        characteristics = service.characteristics.map { value ->
                            if (value.uuid == uuid && value.value == null && !value.failed) {
                                value.copy(value = rendered, failed = !ok)
                            } else {
                                value
                            }
                        },
                    )
                },
            )
        }
        readNext()
    }

    @SuppressLint("MissingPermission")
    private fun readNext() {
        val next = pending.removeFirstOrNull()
        if (next == null) {
            val read = _state.value.readCount
            step(
                "Read $read value${if (read == 1) "" else "s"}.",
                "Everything the device would hand over to an unpaired stranger.",
            )
            finish(ExploreState.DONE)
            return
        }
        // One at a time. A second read issued before this one returns is dropped on the
        // floor without an error, which looks exactly like a device with fewer values.
        val issued = runCatching { gatt?.readCharacteristic(next) }.getOrNull() ?: false
        if (!issued) handler.post { readNext() }
    }

    private fun step(text: String, detail: String? = null) {
        update {
            it.copy(
                steps = it.steps + ExploreStep(
                    atMs = System.currentTimeMillis() - startedAtMs,
                    text = text,
                    detail = detail,
                ),
            )
        }
    }

    private fun fail(reason: String) {
        step("Stopped.", reason)
        update { it.copy(state = ExploreState.FAILED, error = reason) }
        finished = true
        handler.removeCallbacks(timeout)
        close()
    }

    private fun finish(state: ExploreState) {
        if (finished) return
        finished = true
        handler.removeCallbacks(timeout)
        update { it.copy(state = state) }
        close()
    }

    private fun update(block: (Exploration) -> Exploration) {
        _state.value = block(_state.value)
    }

    private companion object {
        /** Android's undocumented catch-all GATT failure. */
        const val GATT_ERROR = 133

        /** Long enough for a slow device, short enough not to look frozen. */
        const val OVERALL_TIMEOUT_MS = 20_000L
    }
}
