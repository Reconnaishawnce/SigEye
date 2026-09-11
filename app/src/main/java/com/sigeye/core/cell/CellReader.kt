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
import android.telephony.CellIdentityNr
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Reads whichever cell the phone is camped on.
 *
 * Polls rather than registering a callback: the callback API changed shape at API 31 and
 * the poll is simple, cheap at a few seconds apart, and gives the neighbour list in the
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

        // The registered cell is the one we are camped on; the rest are neighbours.
        val registered = cells.firstOrNull { it.isRegistered } ?: cells.first()
        val neighbours = cells.count { it !== registered }

        return when (registered) {
            is CellInfoLte -> lte(registered, neighbours, nowMs)
            is CellInfoWcdma -> wcdma(registered, neighbours, nowMs)
            is CellInfoGsm -> gsm(registered, neighbours, nowMs)
            else -> nr(registered, neighbours, nowMs)
        }
    }

    private fun lte(info: CellInfoLte, neighbours: Int, nowMs: Long): CellSample {
        val id = info.cellIdentity
        val strength = info.cellSignalStrength
        return CellSample(
            technology = "LTE",
            operator = operatorOf(id.mccString, id.mncString),
            cellId = id.ci.takeIf { it != Int.MAX_VALUE }?.toLong(),
            areaCode = id.tac.takeIf { it != Int.MAX_VALUE },
            pci = id.pci.takeIf { it != Int.MAX_VALUE },
            channel = id.earfcn.takeIf { it != Int.MAX_VALUE },
            dbm = strength.rsrp.takeIf { it != Int.MAX_VALUE } ?: strength.dbm,
            level = strength.level,
            atMs = nowMs,
            neighbours = neighbours,
        )
    }

    private fun wcdma(info: CellInfoWcdma, neighbours: Int, nowMs: Long): CellSample {
        val id = info.cellIdentity
        return CellSample(
            technology = "WCDMA",
            operator = operatorOf(id.mccString, id.mncString),
            cellId = id.cid.takeIf { it != Int.MAX_VALUE }?.toLong(),
            areaCode = id.lac.takeIf { it != Int.MAX_VALUE },
            pci = id.psc.takeIf { it != Int.MAX_VALUE },
            channel = id.uarfcn.takeIf { it != Int.MAX_VALUE },
            dbm = info.cellSignalStrength.dbm,
            level = info.cellSignalStrength.level,
            atMs = nowMs,
            neighbours = neighbours,
        )
    }

    private fun gsm(info: CellInfoGsm, neighbours: Int, nowMs: Long): CellSample {
        val id = info.cellIdentity
        return CellSample(
            technology = "GSM",
            operator = operatorOf(id.mccString, id.mncString),
            cellId = id.cid.takeIf { it != Int.MAX_VALUE }?.toLong(),
            areaCode = id.lac.takeIf { it != Int.MAX_VALUE },
            pci = null,
            channel = id.arfcn.takeIf { it != Int.MAX_VALUE },
            dbm = info.cellSignalStrength.dbm,
            level = info.cellSignalStrength.level,
            atMs = nowMs,
            neighbours = neighbours,
        )
    }

    /** 5G, which only exists from API 29. */
    private fun nr(info: CellInfo, neighbours: Int, nowMs: Long): CellSample? {
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
            neighbours = neighbours,
        )
    }

    private fun operatorOf(mcc: String?, mnc: String?): String? =
        if (mcc.isNullOrBlank() || mnc.isNullOrBlank()) null else mcc + mnc

    private companion object {
        const val TAG = "SigEye/Cell"
    }
}
