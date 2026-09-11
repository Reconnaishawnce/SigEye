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
    /** Numbered walkthrough, shown behind "How this works" on the experiment screen. */
    val howTo: List<String> = emptyList(),
    /** What the output means once you have it. */
    val reading: String? = null,
    /** The honest limits. Shown in red, because this is the part that gets skipped. */
    val limits: String? = null,
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
    const val CELLS = "cells"
    const val ABSORPTION = "body"
    const val RADAR = "radar"
    const val MICROWAVE = "microwave"
    const val MOTION = "motion"

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
            howTo = listOf(
                "Let the list fill for a few seconds.",
                "Tap Pause to freeze it, then tap any device to open its " +
                    "record.",
                "Give it a nickname, file it into a list, watch it, mute it, or " +
                    "locate it.",
                "Muting is global: a muted device is dropped before counting in " +
                    "every experiment.",
            ),
            reading = "Sort by Closest to find what is near you, or Chattiest to find " +
                "beacons, which advertise far more often than phones.",
            limits = "A nickname sticks to an address, and phones change theirs " +
                "every fifteen minutes or so. Name fixed hardware, not people's " +
                "phones.",
        ),
        Experiment(
            id = RADAR,
            title = "Proximity Radar",
            blurb = "Everything around you, arranged by how close it sounds.",
            teaches = "Signal strength is a usable proxy for distance and a useless one " +
                "for direction. Filter to a watchlist or a list of your own to hunt for " +
                "one kind of thing.",
            category = Experiment.Category.TOOLS,
            status = Experiment.Status.READY,
            howTo = listOf(
                "Pick a filter: everything, your watchlist, flagged vendors, or " +
                    "one of your lists.",
                "Pinch to zoom. Zooming narrows the dB range the rings cover, " +
                    "which gives more resolution close to you.",
                "Tap a blip to select it and see its signal, range and trend.",
                "Tap Locate to hunt it down on foot.",
            ),
            reading = "Distance from the centre is signal strength, strongest in the " +
                "middle. A blip drifting inward is getting closer.",
            limits = "The angle is not a direction. One antenna cannot measure a " +
                "bearing, so the angle is only a hash of the address that keeps " +
                "each device in its own spot. Never read the radar as a map.",
        ),
        Experiment(
            id = WATCHLIST,
            title = "Signal Watch",
            blurb = "Alerts when something you care about comes into range.",
            teaches = "Which identifiers survive address randomisation and which do not. " +
                "Manufacturer prefixes and service data outlive a rotating MAC.",
            category = Experiment.Category.TOOLS,
            status = Experiment.Status.READY,
            howTo = listOf(
                "Turn on Watching to run in the background, with the screen " +
                    "off.",
                "Add a rule, or open the Device Inspector and tap Watch on " +
                    "something you can see.",
                "Set the signal floor to say how close it has to be before you " +
                    "care.",
                "The cooldown keeps one arrival to one buzz rather than one per " +
                    "packet.",
            ),
            reading = "Recent hits show what fired, when, and how strong it was at " +
                "the time.",
            limits = "Rules matching an address only work on hardware with a fixed " +
                "one. Manufacturer prefixes, company IDs and service data " +
                "survive address randomisation; addresses do not.",
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
            howTo = listOf(
                "Put the phone where it will live - a windowsill facing the " +
                    "tracks beats the next room by a wide margin.",
                "Press Start and leave it. The first twenty seconds record what " +
                    "is already here; the next minute learns the normal rate.",
                "Press Train now whenever you actually hear one, to label the " +
                    "CSV.",
                "Pull the CSV later and compare the spike column against your " +
                    "labels.",
            ),
            reading = "The chart shows new devices per bin. The dashed line is the " +
                "usual rate and red dots are bursts.",
            limits = "It cannot tell a train from any other crowd. A bus, a school " +
                "emptying or a delivery van will all raise the count.",
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
            howTo = listOf(
                "Set the signal floor to define what counts as here - around " +
                    "-70 dBm is a room, -85 is a building.",
                "Stand with a group you have actually counted.",
                "Adjust devices per person until the estimate matches the real " +
                    "number.",
                "That figure is now roughly right for this kind of place.",
            ),
            reading = "The radar shows what is present now, strongest at the centre. " +
                "The headline uses only devices heard in the last minute.",
            limits = "It counts devices, not people. Laptops, televisions and " +
                "printers belong to nobody, and one person can carry three " +
                "advertisers.",
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
            howTo = listOf(
                "Leave it running for at least twenty minutes - the classes " +
                    "need time to separate.",
                "Pause the list when you want to tap something.",
                "Tap any device for its full record, and to name, watch or mute " +
                    "it.",
            ),
            reading = "Resident means a device held one address for over twenty " +
                "minutes, which means fixed hardware. Those are the trustworthy " +
                "rows.",
            limits = "Passing counts are inflated by address randomisation: one " +
                "phone walking past can appear as several devices over an " +
                "evening.",
        ),
        Experiment(
            id = MOTION,
            title = "RF Motion Detector",
            blurb = "Notices someone crossing a radio path.",
            teaches = "A body reflects 2.4 GHz, so moving one changes how reflections add " +
                "and cancel at the receiver. The signal becomes unstable long before it " +
                "becomes weak, which is why this watches steadiness rather than strength.",
            category = Experiment.Category.SENSING,
            status = Experiment.Status.READY,
            needs = "A few Bluetooth devices sitting still nearby to use as links.",
            howTo = listOf(
                "Put the phone down where it will stay, somewhere with a television, " +
                    "a speaker or earbuds within range.",
                "Press Calibrate and then leave the room, or at least hold still, for " +
                    "twenty-five seconds.",
                "Anything moving during calibration is learned as normal, which is the " +
                    "one way to make this useless.",
                "Walk back in and watch the score cross the trigger.",
            ),
            reading = "The score is how far the steadiest links have strayed from their " +
                "own calm behaviour, in standard deviations. Two links have to agree " +
                "before it calls motion.",
            limits = "It cannot tell you who, where, or how many - only that the room " +
                "stopped being still. A fan, a door swinging or a cat will all trip it.",
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
            id = ABSORPTION,
            title = "Body Absorption",
            blurb = "Turn slowly in a circle and find your own shadow.",
            teaches = "You are mostly water and water absorbs 2.4 GHz, so a notch appears " +
                "when your torso sits between the phone and the source. Several dB of it " +
                "is a body; much more means a wall joined in.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.READY,
            needs = "A compass. Most phones have one, but it must be calibrated.",
            howTo = listOf(
                "Pick a chatty source. The list shows how many packets a second " +
                    "each one sends - anything under about three will not fill the " +
                    "sectors in time.",
                "Hold the phone flat against your chest, screen facing out. " +
                    "This matters more than anything else: your torso has to be " +
                    "between the phone and the source.",
                "Turn slowly on the spot, a full circle in about thirty " +
                    "seconds.",
                "Run it at least twice from the same place, facing the same way " +
                    "to start.",
            ),
            reading = "Radius is signal strength and north is up, so a notch is a " +
                "direction something was absorbing from. The dB figure is the " +
                "difference between the best and worst direction - a few dB is " +
                "a body.",
            limits = "One sweep cannot tell your body from the room, because a " +
                "reflection makes a notch too. Only repeated sweeps can: yours " +
                "turns with you, the room's stays put.",
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
            id = MICROWAVE,
            title = "Microwave Interference",
            blurb = "Watch an oven trample the 2.4 GHz band.",
            teaches = "A consumer microwave leaks around 2.45 GHz, right in the middle of " +
                "the band Bluetooth uses. Measure how many advertisements survive with " +
                "it off, then with it on.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.READY,
            needs = "A microwave oven, and something to heat in it.",
            howTo = listOf(
                "Stand near the microwave with a Bluetooth device in the room, " +
                    "and put a cup of water in the oven so it has something to " +
                    "heat.",
                "Record the baseline with the oven off, for thirty seconds.",
                "Start the oven, then record the test phase for thirty seconds.",
                "Stop, and compare.",
            ),
            reading = "The measure is how many advertisements a second reach the " +
                "phone. A microwave leaking into the band drowns them out, so " +
                "the rate falls and the surviving packets read weaker.",
            limits = "It cannot see the oven directly, only the damage. A modern " +
                "well-sealed oven may show almost nothing, which is itself the " +
                "answer: your kitchen Wi-Fi problem is somewhere else.",
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
            id = CELLS,
            title = "Cell Handovers",
            blurb = "How often your phone changes tower.",
            teaches = "Standing still, handovers are rare. On a train they come every " +
                "minute or two, and a handover taken while signal was still strong is " +
                "load balancing rather than lost coverage.",
            category = Experiment.Category.MAPPING,
            status = Experiment.Status.READY,
            needs = "A SIM. Nothing to read in aeroplane mode.",
            howTo = listOf(
                "Open it and leave it while you travel.",
                "Standing still, expect almost no handovers - that is the " +
                    "control.",
                "On a train or in a car they come every minute or two.",
            ),
            reading = "Each row shows how long the previous cell was held and the " +
                "signal you left it at. A handover taken at strong signal is " +
                "load balancing, not lost coverage.",
            limits = "Modems withhold cell identity regularly. Those readings are " +
                "recorded but never counted as movement, so the total is a " +
                "floor, not an exact count.",
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
            howTo = listOf(
                "Let it listen. Anything speaking a known format appears.",
                "Use the protocol chips to isolate one format.",
                "Pause before tapping a row - beacons advertise fast and the " +
                    "list reorders.",
            ),
            reading = "Each row names the protocol and decodes its fields. Apple Find " +
                "My means an AirTag or a device advertising for the offline " +
                "finding network.",
            limits = "Most devices advertise nothing structured, so an empty list is " +
                "normal in a quiet place. An unrecognised format falls back to " +
                "naming the vendor.",
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
