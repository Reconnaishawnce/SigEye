package com.sigeye.experiments.follow

import android.content.Context
import com.sigeye.core.analysis.identity.FollowTuning

/**
 * The numbers a follow runs on, kept where the person running it can change them.
 *
 * Every one of these is a judgement call rather than a measurement, and the right value
 * depends on things the app cannot see: how fast you walk, whether their phone is in a hand
 * or a back pocket, whether you are in a street or a shopping centre. Baking them in and
 * calling them constants would be pretending otherwise.
 *
 * The drop-off is the one that matters. It is the entire elimination, and it trades the same
 * thing in both directions - shorter and a body shadow loses you the real target, longer and
 * half the street stays on the list. A minute is where I would start.
 */
class FollowSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("follow_tuning", Context.MODE_PRIVATE)

    fun load(): FollowTuning {
        val d = FollowTuning.DEFAULT
        return FollowTuning(
            baselineMs = prefs.getLong(KEY_BASELINE, d.baselineMs),
            dropAfterMs = prefs.getLong(KEY_DROP, d.dropAfterMs),
            circleMs = prefs.getLong(KEY_CIRCLE, d.circleMs),
            shortlistMax = prefs.getInt(KEY_SHORTLIST, d.shortlistMax),
            listableAt = prefs.getInt(KEY_LISTABLE, d.listableAt),
            rebaselineAfterMs = prefs.getLong(KEY_REBASELINE, d.rebaselineAfterMs),
            minPackets = prefs.getInt(KEY_MIN_PACKETS, d.minPackets),
            lostAfterMs = prefs.getLong(KEY_LOST, d.lostAfterMs),
            carriedDbm = prefs.getInt(KEY_CARRIED, d.carriedDbm),
            carriedSpreadDb = prefs.getFloat(KEY_SPREAD, d.carriedSpreadDb.toFloat()).toDouble(),
            carriedAfterMs = prefs.getLong(KEY_CARRIED_AFTER, d.carriedAfterMs),
            bridgeAtOrBelow = prefs.getInt(KEY_BRIDGE, d.bridgeAtOrBelow),
            // Stored as a level with zero standing in for off, because a nullable int has
            // no natural absent value in these preferences and zero is not a valid RSSI.
            autoMuteAboveDbm = prefs.getInt(KEY_AUTO_MUTE, 0).takeIf { it != 0 },
        )
    }

    fun save(tuning: FollowTuning) {
        prefs.edit()
            .putLong(KEY_BASELINE, tuning.baselineMs)
            .putLong(KEY_DROP, tuning.dropAfterMs)
            .putLong(KEY_CIRCLE, tuning.circleMs)
            .putInt(KEY_SHORTLIST, tuning.shortlistMax)
            .putInt(KEY_LISTABLE, tuning.listableAt)
            .putLong(KEY_REBASELINE, tuning.rebaselineAfterMs)
            .putInt(KEY_MIN_PACKETS, tuning.minPackets)
            .putLong(KEY_LOST, tuning.lostAfterMs)
            .putInt(KEY_CARRIED, tuning.carriedDbm)
            .putFloat(KEY_SPREAD, tuning.carriedSpreadDb.toFloat())
            .putLong(KEY_CARRIED_AFTER, tuning.carriedAfterMs)
            .putInt(KEY_BRIDGE, tuning.bridgeAtOrBelow)
            .putInt(KEY_AUTO_MUTE, tuning.autoMuteAboveDbm ?: 0)
            .apply()
    }

    fun reset() = save(FollowTuning.DEFAULT)

    private companion object {
        const val KEY_BASELINE = "baseline_ms"
        const val KEY_DROP = "drop_after_ms"
        const val KEY_CIRCLE = "circle_ms"
        const val KEY_SHORTLIST = "shortlist_max"
        const val KEY_LISTABLE = "listable_at"
        const val KEY_REBASELINE = "rebaseline_after_ms"
        const val KEY_MIN_PACKETS = "min_packets"
        const val KEY_LOST = "lost_after_ms"
        const val KEY_CARRIED = "carried_dbm"
        const val KEY_SPREAD = "carried_spread_db"
        const val KEY_CARRIED_AFTER = "carried_after_ms"
        const val KEY_BRIDGE = "bridge_at_or_below"
        const val KEY_AUTO_MUTE = "auto_mute_above_dbm"
    }
}
