package com.sigeye.core

/**
 * One entry on the SigEye home screen.
 *
 * Every experiment declares the permissions it needs rather than the app requesting a
 * union of everything up front. A user who only wants the Wi-Fi channel analyser should
 * never be asked for Bluetooth.
 */
data class Experiment(
    val id: String,
    val title: String,
    val blurb: String,
    /** What the reading actually means, shown on the experiment screen. */
    val teaches: String,
    val status: Status,
) {
    enum class Status { READY, PLANNED }
}

/**
 * The registry. Adding an experiment means adding a row here and a screen for its id.
 * Planned entries are listed deliberately - the roadmap is part of the app, the way
 * Phyphox shows you every experiment it ships.
 */
object Experiments {

    const val TRAIN_SPOTTER = "trainspotter"

    val all: List<Experiment> = listOf(
        Experiment(
            id = TRAIN_SPOTTER,
            title = "Train Spotter",
            blurb = "Counts new Bluetooth devices nearby and flags the bursts.",
            teaches = "A passing train is a hundred phones crossing your radio horizon at " +
                "once. That shows up as a spike in previously unseen addresses.",
            status = Experiment.Status.READY,
        ),
        Experiment(
            id = "beacons",
            title = "Beacon Decoder",
            blurb = "Shows what nearby devices are actually broadcasting.",
            teaches = "iBeacon and Eddystone frames, manufacturer IDs, service UUIDs.",
            status = Experiment.Status.PLANNED,
        ),
        Experiment(
            id = "channels",
            title = "Channel Congestion",
            blurb = "Which Wi-Fi channel is least crowded where you are.",
            teaches = "Why only three of the 2.4 GHz channels are non-overlapping.",
            status = Experiment.Status.PLANNED,
        ),
        Experiment(
            id = "pathloss",
            title = "Path Loss Exponent",
            blurb = "Walk away from a source and measure how fast signal falls off.",
            teaches = "Free space gives about 2.0, a corridor less, walls far more.",
            status = Experiment.Status.PLANNED,
        ),
        Experiment(
            id = "faraday",
            title = "Faraday Cage Test",
            blurb = "How many dB does a microwave, a tin or a lift actually block?",
            teaches = "Shielding, apertures, and why the seams matter more than the metal.",
            status = Experiment.Status.PLANNED,
        ),
    )

    fun byId(id: String): Experiment? = all.firstOrNull { it.id == id }
}
