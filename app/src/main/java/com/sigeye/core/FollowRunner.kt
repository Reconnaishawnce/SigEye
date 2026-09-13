package com.sigeye.core

import com.sigeye.core.analysis.identity.FollowSession
import com.sigeye.core.analysis.identity.FollowState
import com.sigeye.core.analysis.identity.FollowTuning
import com.sigeye.core.ble.Advert
import com.sigeye.core.ble.shape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * The follow, held where the screen cannot take it away.
 *
 * A follow is half an hour of walking and the phone has to be in a pocket for most of it.
 * Holding a lit screen for a mile is conspicuous, costs the battery, and is the opposite of
 * what somebody demonstrating this would do - so the session lives here, driven by
 * [ScanService], and the screen merely reads it. Closing the screen no longer ends anything;
 * it stops being the thing that keeps the radio open, and the service takes over.
 *
 * The same shape as [Recordings], and for the same reason: a measurement that belongs to a
 * composition is a measurement that ends when somebody checks a message.
 *
 * One at a time, deliberately. Two follows would share one radio and produce two half
 * pictures, and there is no sensible way to show both.
 */
object FollowRunner {

    private var session: FollowSession = FollowSession()

    private val _state = MutableStateFlow(FollowState())

    /** What the follow can say right now. Recomputed on the service tick. */
    val state: StateFlow<FollowState> = _state

    /** True while a follow exists at all, whether or not the screen is open. */
    val active: Boolean get() = _state.value.phase != com.sigeye.core.analysis.identity.FollowPhase.IDLE

    /**
     * Hands out the session so a screen can drive it directly.
     *
     * Deliberately not wrapped in a narrower interface. The screen needs almost all of it -
     * baselines, probes, locking, re-baselining - and a facade would be the same surface
     * with an extra place for the two to disagree.
     */
    fun session(): FollowSession = session

    fun begin(tuning: FollowTuning): FollowSession {
        session = FollowSession(tuning)
        _state.value = FollowState()
        return session
    }

    fun adopt(restored: FollowSession) {
        session = restored
        _state.value = restored.state(System.currentTimeMillis())
    }

    fun end() {
        session = FollowSession()
        _state.value = FollowState()
    }

    fun onAdvert(advert: Advert, book: DeviceBook?) {
        session.observe(
            address = advert.address,
            rssi = advert.rssi,
            atMs = advert.atMs,
            label = book?.nicknameOf(advert.address) ?: advert.name,
            vendor = advert.vendor,
            isRandom = advert.isRandomAddress,
            // Without this the session has nothing to recognize a device by once it puts
            // on a new address, and a half-hour follow ends at the first rotation.
            shape = advert.shape(),
        )
    }

    /**
     * @param ignoreList given so a mute can travel with a device through a rotation. Muting
     *   your own earbuds is worthless if it expires every fifteen minutes.
     */
    fun tick(nowMs: Long, ignoreList: IgnoreList? = null) {
        _state.value = session.state(nowMs)
        if (ignoreList != null) {
            val inherited = session.drainNewMutes()
            if (inherited.isNotEmpty()) {
                inherited.forEach(ignoreList::add)
                session.ignored = ignoreList.addresses.value
            }
        }
    }

    /** One line for the ongoing notification, which is the only view of this with the screen off. */
    fun summary(): String {
        val state = _state.value
        return String.format(
            Locale.US,
            "Follow Me: %d of %d still with them",
            state.stillIn.size,
            state.poolSize,
        )
    }
}
