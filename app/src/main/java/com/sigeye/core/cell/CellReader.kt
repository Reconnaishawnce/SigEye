package com.sigeye.core.cell

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Reads whichever cell the phone is camped on.
 *
 * Polls rather than registering a callback: the callback API changed shape at API 31 and
 * the poll is simple, cheap at a few seconds apart, and gives the neighbor list in the
 * same call. Requires location permission - Android treats cell identity as location,
 * which it plainly is.
 */
class CellReader(context: Context) {

    private val telephony =
        context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    val isAvailable: Boolean get() = telephony != null

    /** Null when there is no SIM, no permission, or the modem returns nothing. */
    @SuppressLint("MissingPermission") // caller gates on ACCESS_FINE_LOCATION
    fun read(nowMs: Long): CellSample? {
        val manager = telephony ?: return null
        val cells = try {
            manager.allCellInfo
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission for cell info", e)
            return null
        } catch (e: Exception) {
            Log.w(TAG, "allCellInfo threw", e)
            return null
        } ?: return null

        if (cells.isEmpty()) return null

        // The registered cell is the one we are camped on; the rest are neighbors.
        val registered = cells.firstOrNull { it.isRegistered } ?: cells.first()
        val neighbors = cells.count { it !== registered }

        return when (registered) {
            is CellInfoLte -> lte(registered, neighbors, nowMs)
            is CellInfoWcdma -> wcdma(registered, neighbors, nowMs)
            is CellInfoGsm -> gsm(registered, neighbors, nowMs)
            else -> nr(registered, neighbors, nowMs)
        }
    }

    private fun lte(info: CellInfoLte, neighbors: Int, nowMs: Long): CellSample {
        val id = info.cellIdentity
        val strength = info.cellSignalStrength
        return CellSample(
            technology = "LTE",
            operator = operatorOf(mccOf(id), mncOf(id)),
            cellId = id.ci.takeIf { it != Int.MAX_VALUE }?.toLong(),
            areaCode = id.tac.takeIf { it != Int.MAX_VALUE },
            pci = id.pci.takeIf { it != Int.MAX_VALUE },
            channel = id.earfcn.takeIf { it != Int.MAX_VALUE },
            dbm = strength.rsrp.takeIf { it != Int.MAX_VALUE } ?: strength.dbm,
            level = strength.level,
            atMs = nowMs,
            neighbors = neighbors,
        )
    }

    private fun wcdma(info: CellInfoWcdma, neighbors: Int, nowMs: Long): CellSample {
        val id = info.cellIdentity
        return CellSample(
            technology = "WCDMA",
            operator = operatorOf(mccOf(id), mncOf(id)),
            cellId = id.cid.takeIf { it != Int.MAX_VALUE }?.toLong(),
            areaCode = id.lac.takeIf { it != Int.MAX_VALUE },
            pci = id.psc.takeIf { it != Int.MAX_VALUE },
            channel = id.uarfcn.takeIf { it != Int.MAX_VALUE },
            dbm = info.cellSignalStrength.dbm,
            level = info.cellSignalStrength.level,
            atMs = nowMs,
            neighbors = neighbors,
        )
    }

    private fun gsm(info: CellInfoGsm, neighbors: Int, nowMs: Long): CellSample {
        val id = info.cellIdentity
        return CellSample(
            technology = "GSM",
            operator = operatorOf(mccOf(id), mncOf(id)),
            cellId = id.cid.takeIf { it != Int.MAX_VALUE }?.toLong(),
            areaCode = id.lac.takeIf { it != Int.MAX_VALUE },
            pci = null,
            channel = id.arfcn.takeIf { it != Int.MAX_VALUE },
            dbm = info.cellSignalStrength.dbm,
            level = info.cellSignalStrength.level,
            atMs = nowMs,
            neighbors = neighbors,
        )
    }

    /** 5G, which only exists from API 29. */
    private fun nr(info: CellInfo, neighbors: Int, nowMs: Long): CellSample? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (info !is CellInfoNr) return null
        val id = info.cellIdentity as? CellIdentityNr ?: return null
        val strength = info.cellSignalStrength as? CellSignalStrengthNr
        return CellSample(
            technology = "5G NR",
            operator = operatorOf(id.mccString, id.mncString),
            cellId = id.nci.takeIf { it != Long.MAX_VALUE },
            areaCode = id.tac.takeIf { it != Int.MAX_VALUE },
            pci = id.pci.takeIf { it != Int.MAX_VALUE },
            channel = id.nrarfcn.takeIf { it != Int.MAX_VALUE },
            dbm = strength?.ssRsrp?.takeIf { it != Int.MAX_VALUE } ?: info.cellSignalStrength.dbm,
            level = info.cellSignalStrength.level,
            atMs = nowMs,
            neighbors = neighbors,
        )
    }

    /**
     * Network codes, in whichever form this Android version offers.
     *
     * The string getters arrived in API 28 and this app supports 26, so on Android 8 they
     * are not merely absent - calling them throws NoSuchMethodError and takes the whole
     * experiment down. The integer getters they replaced are deprecated but present all
     * the way back, and carry the same value with leading zeroes lost, which for a
     * display string is worth the trade.
     */
    @Suppress("DEPRECATION")
    private fun mccOf(id: CellIdentityLte): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            id.mccString
        } else {
            id.mcc.takeIf { it != Int.MAX_VALUE }?.toString()
        }

    @Suppress("DEPRECATION")
    private fun mncOf(id: CellIdentityLte): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            id.mncString
        } else {
            id.mnc.takeIf { it != Int.MAX_VALUE }?.toString()
        }

    @Suppress("DEPRECATION")
    private fun mccOf(id: CellIdentityWcdma): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            id.mccString
        } else {
            id.mcc.takeIf { it != Int.MAX_VALUE }?.toString()
        }

    @Suppress("DEPRECATION")
    private fun mncOf(id: CellIdentityWcdma): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            id.mncString
        } else {
            id.mnc.takeIf { it != Int.MAX_VALUE }?.toString()
        }

    @Suppress("DEPRECATION")
    private fun mccOf(id: CellIdentityGsm): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            id.mccString
        } else {
            id.mcc.takeIf { it != Int.MAX_VALUE }?.toString()
        }

    @Suppress("DEPRECATION")
    private fun mncOf(id: CellIdentityGsm): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            id.mncString
        } else {
            id.mnc.takeIf { it != Int.MAX_VALUE }?.toString()
        }

    private fun operatorOf(mcc: String?, mnc: String?): String? =
        if (mcc.isNullOrBlank() || mnc.isNullOrBlank()) null else mcc + mnc

    private companion object {
        const val TAG = "SigEye/Cell"
    }
}
