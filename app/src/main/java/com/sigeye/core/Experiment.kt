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
    /** What the reading actually means. */
    val teaches: String,
    val category: Category,
    val status: Status,
    /** Set when the phone, or a second device, may simply not be able to do this. */
    val needs: String? = null,
) {
    enum class Status { READY, PLANNED }

    enum class Category(val label: String, val blurb: String) {
        TOOLS("Tools", "See and name what is around you."),
        SENSING("Counting and sensing", "Who and what is moving past."),
        PHYSICS("RF physics", "How radio actually behaves in your rooms."),
        MAPPING("Mapping", "Survey a building, a route, a band."),
        PRIVACY("What is being broadcast", "What your neighbourhood gives away."),
    }
}

/**
 * The registry. Adding an experiment means adding a row here and a screen for its id.
 *
 * Planned entries are listed deliberately - the roadmap is part of the app, the way
 * Phyphox shows you every experiment it ships. Anything hardware-dependent says so in
 * [Experiment.needs] rather than failing silently once opened.
 */
object Experiments {

    const val TRAIN_SPOTTER = "trainspotter"
    const val INSPECTOR = "inspector"
    const val WATCHLIST = "watchlist"
    const val BEACONS = "beacons"
    const val DWELL = "dwell"
    const val CROWD = "crowd"

    val all: List<Experiment> = listOf(

        // ------------------------------------------------------------- tools
        Experiment(
            id = INSPECTOR,
            title = "Device Inspector",
            blurb = "Everything broadcasting around you, decoded.",
            teaches = "Who made it, what it is saying, how close it is. Name devices and " +
                "group them into lists that other experiments can use.",
            category = Experiment.Category.TOOLS,
            status = Experiment.Status.READY,
        ),
        Experiment(
            id = WATCHLIST,
            title = "Signal Watch",
            blurb = "Alerts when something you care about comes into range.",
            teaches = "Which identifiers survive address randomisation and which do not. " +
                "Manufacturer prefixes and service data outlive a rotating MAC.",
            category = Experiment.Category.TOOLS,
            status = Experiment.Status.READY,
        ),

        // ----------------------------------------------------------- sensing
        Experiment(
            id = TRAIN_SPOTTER,
            title = "Train Spotter",
            blurb = "Counts new Bluetooth devices nearby and flags the bursts.",
            teaches = "A passing train is a hundred phones crossing your radio horizon at " +
                "once. That shows up as a spike in previously unseen addresses.",
            category = Experiment.Category.SENSING,
            status = Experiment.Status.READY,
        ),
        Experiment(
            id = "speed",
            title = "Speed Estimator",
            blurb = "How fast was that train, from the shape of the signal.",
            teaches = "Signal rises and falls as a vehicle passes. The width of that " +
                "envelope, plus a known distance, gives speed.",
            category = Experiment.Category.SENSING,
            status = Experiment.Status.PLANNED,
        ),
        Experiment(
            id = CROWD,
            title = "Crowd Counter",
            blurb = "Roughly how many people are around you.",
            teaches = "Devices are a proxy for people, and a bad one until calibrated " +
                "against a headcount you actually know.",
            category = Experiment.Category.SENSING,
            status = Experiment.Status.READY,
        ),
        Experiment(
            id = DWELL,
            title = "Dwell Time",
            blurb = "Who is passing through, and who lives here.",
            teaches = "Sort devices by how long they stay. Under two minutes is traffic, " +
                "over twenty is a fixture - and only the fixtures are trustworthy, " +
                "because a phone changes address before it can become one.",
            category = Experiment.Category.SENSING,
            status = Experiment.Status.READY,
        ),
        Experiment(
            id = "motion",
            title = "RF Motion Detector",
            blurb = "Notices someone crossing a radio path.",
            teaches = "A body between two radios perturbs the signal measurably. This is " +
                "Wi-Fi sensing, and it needs no camera.",
            category = Experiment.Category.SENSING,
            status = Experiment.Status.PLANNED,
        ),
        Experiment(
            id = "rhythm",
            title = "Daily Rhythm",
            blurb = "The week where you live, in device counts.",
            teaches = "Commute peaks, quiet Sundays, the pub emptying. Long-run logging " +
                "turns noise into a schedule.",
            category = Experiment.Category.SENSING,
            status = Experiment.Status.PLANNED,
        ),

        // ----------------------------------------------------------- physics
        Experiment(
            id = "pathloss",
            title = "Path Loss Exponent",
            blurb = "Walk away from a source and measure how fast signal falls off.",
            teaches = "Free space gives about 2.0, a corridor less because it guides the " +
                "wave, walls far more. One number that characterises an environment.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.PLANNED,
        ),
        Experiment(
            id = "fading",
            title = "Multipath Fading",
            blurb = "Stand still and watch the signal move anyway.",
            teaches = "Reflections add and cancel, so signal swings several dB with " +
                "nothing moving. This is why signal strength is a poor ruler.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.PLANNED,
        ),
        Experiment(
            id = "body",
            title = "Body Absorption",
            blurb = "Turn slowly in a circle and find your own shadow.",
            teaches = "You are mostly water and water absorbs 2.4 GHz. A deep null appears " +
                "when your torso is between phone and source.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.PLANNED,
        ),
        Experiment(
            id = "bands",
            title = "2.4 vs 5 GHz",
            blurb = "Which band actually gets through your walls.",
            teaches = "Same router, two bands, one wall. Higher frequencies are absorbed " +
                "harder, and you can measure by how much.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.PLANNED,
        ),
        Experiment(
            id = "faraday",
            title = "Faraday Cage Test",
            blurb = "How many dB does a microwave, a tin or a lift really block?",
            teaches = "Shielding, apertures, and why the seams matter more than the metal.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.PLANNED,
        ),
        Experiment(
            id = "microwave",
            title = "Microwave Interference",
            blurb = "Watch an oven trample the 2.4 GHz band.",
            teaches = "A consumer microwave leaks around 2.45 GHz, right in the ISM band. " +
                "The clearest answer to why Wi-Fi dies in the kitchen.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.PLANNED,
        ),
        Experiment(
            id = "rtt",
            title = "True Ranging (Wi-Fi RTT)",
            blurb = "Distance by timing light, not by guessing from loudness.",
            teaches = "802.11mc measures time of flight in centimetres. Put it beside an " +
                "RSSI estimate and the gap is the whole lesson.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.PLANNED,
            needs = "802.11mc support on both this phone and the access point.",
        ),
        Experiment(
            id = "linkbudget",
            title = "Two-Phone Link Budget",
            blurb = "Control both ends and watch the numbers line up.",
            teaches = "Step the transmit power and the received signal tracks it one for " +
                "one. The calibration rig for every other experiment here.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.PLANNED,
            needs = "A second Android device running SigEye.",
        ),

        // ----------------------------------------------------------- mapping
        Experiment(
            id = "deadzones",
            title = "Dead Zone Heatmap",
            blurb = "Walk the building, find where the signal dies.",
            teaches = "Where the router should actually go, and how much one wall costs.",
            category = Experiment.Category.MAPPING,
            status = Experiment.Status.PLANNED,
        ),
        Experiment(
            id = "channels",
            title = "Channel Congestion",
            blurb = "Which Wi-Fi channel is least crowded where you are.",
            teaches = "Why only three of the 2.4 GHz channels are non-overlapping, and " +
                "which one your router should be on.",
            category = Experiment.Category.MAPPING,
            status = Experiment.Status.PLANNED,
        ),
        Experiment(
            id = "route",
            title = "Route Logger",
            blurb = "Signal density along a journey.",
            teaches = "Tunnels, stations, dead zones and platform geometry, plotted " +
                "against a GPS track.",
            category = Experiment.Category.MAPPING,
            status = Experiment.Status.PLANNED,
        ),
        Experiment(
            id = "cells",
            title = "Cell Handovers",
            blurb = "How often your phone changes tower on a trip.",
            teaches = "The macro network: cell density, signal quality, and where the " +
                "operator has gaps.",
            category = Experiment.Category.MAPPING,
            status = Experiment.Status.PLANNED,
        ),

        // ----------------------------------------------------------- privacy
        Experiment(
            id = BEACONS,
            title = "Beacon Decoder",
            blurb = "Reads the beacon formats hiding in the noise.",
            teaches = "iBeacon, Eddystone, AltBeacon and Apple's Continuity messages are " +
                "structured data, not blobs. Decoded, they name themselves.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.READY,
        ),
        Experiment(
            id = "randomisation",
            title = "MAC Randomisation",
            blurb = "Which devices around you can be followed, and which cannot.",
            teaches = "Measures how often addresses actually rotate, and lists the ones " +
                "that never do - trackers, tags, cars, headsets.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.PLANNED,
        ),
    )

    fun byId(id: String): Experiment? = all.firstOrNull { it.id == id }

    fun byCategory(): List<Pair<Experiment.Category, List<Experiment>>> =
        Experiment.Category.entries
            .map { category -> category to all.filter { it.category == category } }
            .filter { it.second.isNotEmpty() }

    val readyCount: Int get() = all.count { it.status == Experiment.Status.READY }
}
