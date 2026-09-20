package com.sigeye.core.ble

import android.bluetooth.le.ScanFilter
import android.os.ParcelUuid
import java.util.UUID

/**
 * Filters that keep a scan alive once the screen goes off.
 *
 * Android 8.1 changed the rules: a scan started with no filters returns nothing at all
 * while the screen is off. Not fewer results - none. That is why a recording left running
 * overnight produced a flat line at the moment the display timed out, and why holding the
 * screen awake appeared to be the only thing that worked.
 *
 * A filter list is OR'd, so the job is to build a set that between them match as close to
 * everything as the API can express. Two shapes do the work:
 *
 *  - a service UUID filter with an all-zero mask, which means "do not care about any bit"
 *    and therefore matches any advertisement that carries a service UUID at all;
 *  - a manufacturer data filter per company, with an empty mask, which matches anything
 *    that company advertises regardless of payload.
 *
 * What this cannot do is express "everything". An advertisement with no service UUID and a
 * company identifier outside the list below is invisible while the screen is off, and no
 * arrangement of filters fixes that - the platform provides no wildcard. So the honest
 * design is not to pretend: the same filters are used whether the screen is on or off, so
 * that a count taken at midnight is comparable with one taken at noon. A measurement that
 * silently changes what it includes halfway through is worse than one that includes less.
 *
 * The company identifiers are the ones that account for nearly all phone, wearable and
 * accessory traffic. They are assigned by the Bluetooth SIG and are already in the app's
 * generated tables, which is where these came from rather than from guesswork.
 */
object ScanFilters {

    /**
     * Companies whose devices make up the overwhelming majority of what a phone hears.
     *
     * Apple first because in most rooms it is most of the traffic. The rest are the makers
     * of phones, watches, earbuds and fitness bands - the things a crowd is made of.
     */
    private val COMPANIES = intArrayOf(
        0x004C, // Apple
        0x0006, // Microsoft
        0x00E0, // Google
        0x0075, // Samsung
        0x0087, // Garmin
        0x000F, // Broadcom
        0x0157, // Anhui Huami - Amazfit and Mi Band
        0x0499, // Ruuvi
        0x0059, // Nordic Semiconductor
        0x000D, // Texas Instruments
        0x0001, // Nokia
        0x00D2, // Dialog Semiconductor
        0x0171, // Amazon
        0x03DA, // Tile
        0x09C8, // XUNTONG - tyre sensors, and Flock Safety camera batteries
    )

    /**
     * A zero mask means every bit is "do not care", so this matches any service UUID.
     *
     * It does not match an advertisement that carries no service UUID, which is why the
     * manufacturer filters exist alongside it rather than instead of it.
     */
    private val ANY_SERVICE: ScanFilter
        get() = ScanFilter.Builder()
            .setServiceUuid(
                ParcelUuid(UUID(0L, 0L)),
                ParcelUuid(UUID(0L, 0L)),
            )
            .build()

    /**
     * The filter list to scan with.
     *
     * Always used, screen on or off. See the note above on why consistency beats yield.
     */
    fun broad(): List<ScanFilter> = buildList {
        add(ANY_SERVICE)
        COMPANIES.forEach { company ->
            add(
                ScanFilter.Builder()
                    .setManufacturerData(company, ByteArray(0), ByteArray(0))
                    .build(),
            )
        }
    }

    /** How many makers the filter set names, for a screen that should show its working. */
    val companyCount: Int get() = COMPANIES.size

    /**
     * What a user needs to know about the trade, in one sentence.
     *
     * Shown wherever a long recording is started, because this is the difference between a
     * count somebody can trust overnight and one they cannot.
     */
    const val EXPLANATION =
        "Android returns nothing at all from an unfiltered scan once the screen goes off, " +
            "so this scans with a filter set that matches anything advertising a service " +
            "or coming from one of the common makers. It is used whether the screen is on " +
            "or off, so a count at midnight means the same as one at noon. A device with " +
            "no service UUID from an unusual maker will be missed either way."
}
