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
    const val FARADAY = "faraday"
    const val SPEED = "speed"
    const val FADING = "fading"
    const val DISCOVERY = "discovery"
    const val EXPLORER = "explorer"
    const val WIFI = "wifi"
    const val PLACE = "place"

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
            id = DISCOVERY,
            title = "Discovery",
            blurb = "Learn what is normally here, then watch for what is not.",
            teaches = "A scan of anywhere returns dozens of devices and no way to rank " +
                "them. Almost every useful question is about change rather than presence - " +
                "what arrived, what left, what got closer since last time.",
            category = Experiment.Category.TOOLS,
            status = Experiment.Status.READY,
            needs = "Somewhere to stand still for half a minute while it learns the room.",
            howTo = listOf(
                "Stand where you mean to watch from and start the baseline. Everything " +
                    "heard during it is filed as normal and will never be reported.",
                "Thirty seconds suits most places. Somewhere with slow beacons needs " +
                    "longer, or they will be announced as arrivals afterwards.",
                "After that, only new devices appear. Anything getting steadily stronger " +
                    "is marked as approaching and sorted to the top.",
                "Tap 'not interesting' to file something away, or add the whole list to " +
                    "the baseline once you have identified it.",
                "For the at-home use: save a snapshot of a room you trust, then take " +
                    "another next week and compare the two.",
                "For the travelling-with-you use: snapshot three genuinely different " +
                    "places and look at what fixed addresses appear in all of them.",
            ),
            reading = "Arrivals are devices unheard of during the baseline. The arrow is " +
                "the signal trend, so a rising one is coming towards you. A comparison of " +
                "two snapshots lists what arrived, what left, and what shifted by more " +
                "than eight dB, which is about where a change stops being multipath.",
            limits = "Randomised addresses defeat most of this and the app says so rather " +
                "than pretending otherwise: a phone changes address roughly every fifteen " +
                "minutes, which is indistinguishable from a stranger arriving, so " +
                "suspected rotations are listed but not alerted on, and snapshot " +
                "comparisons cover only fixed addresses. Something with no fixed address " +
                "that never advertises during your baseline cannot be caught this way.",
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
            id = SPEED,
            title = "Speed Estimator",
            blurb = "How fast was that, from the shape of the signal.",
            teaches = "A device going past is loudest when it draws level. Six dB below " +
                "that peak is a known greater range, and a known range at a known distance " +
                "from the track is a right-angled triangle - so the length of track " +
                "between the two crossings falls out, and time gives speed.",
            category = Experiment.Category.SENSING,
            status = Experiment.Status.READY,
            needs = "A known perpendicular distance to the road or track.",
            howTo = listOf(
                "Measure or pace the distance from where the phone sits to the middle of " +
                    "the track, and set it. Everything scales directly off this number.",
                "Press Watch for passes and leave it.",
                "Results appear a few seconds after each vehicle has gone - a pass is only " +
                    "recognisable once it is over.",
                "Check one against something you know, a road with a speed limit will do, " +
                    "and adjust the path loss exponent until it agrees.",
            ),
            reading = "Each row shows the peak signal, how long the crossing took and how " +
                "many readings it rested on. Passes marked rough had lopsided approach and " +
                "departure, which usually means something else was changing.",
            limits = "It assumes a straight line at constant speed passing at the distance " +
                "you gave. A device slowing down, stopping, or on a different track will " +
                "still produce a number, and it will be wrong.",
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
                "If nothing fires unless you stand next to the phone, open Settings and " +
                    "lower the noise floor - that is usually the culprit.",
                "If you know where someone would walk, choose the links yourself: a " +
                    "beacon on the far side of a doorway makes a tripwire.",
            ),
            reading = "The score is how far the steadiest links have strayed from their " +
                "own calm behaviour, in standard deviations. Level and jitter are shown " +
                "apart: level is someone blocking the path, jitter is someone moving " +
                "anywhere in the room. Jitter is the sensitive one.",
            limits = "It cannot tell you who, where, or how many - only that the room " +
                "stopped being still. A fan, a door swinging or a cat will all trip it.",
        ),
        Experiment(
            id = PLACE,
            title = "Place Profiler",
            blurb = "Leave the phone somewhere for hours and see the shape of the place.",
            teaches = "Somewhere busy and somewhere quiet look identical in a snapshot and " +
                "nothing alike over an afternoon. A car park is flat and full overnight; " +
                "a corridor is spiky and empty. The shape is the fingerprint.",
            category = Experiment.Category.SENSING,
            status = Experiment.Status.READY,
            needs = "A few hours, and a charger. The screen has to stay open.",
            howTo = listOf(
                "Choose a slice length. Ten minutes over four hours gives twenty-four " +
                    "slices, which is enough shape without every slice being noise.",
                "Start it, plug the phone in, and leave it somewhere it will not be moved.",
                "Come back hours later. Under an hour tells you almost nothing, and the " +
                    "screen says so rather than drawing a confident chart of noise.",
                "Read the residents list for what lives there, and the movers list for " +
                    "anything that normally sits still and stopped doing so.",
            ),
            reading = "Bars are devices present per slice; the marks underneath are how " +
                "much arrived or left. A resident was around for several slices, a " +
                "fixture for most of them. A fixture whose signal steps abruptly is the " +
                "most interesting thing here - something that had been still was moved, " +
                "or something large moved between it and the phone.",
            limits = "The screen has to stay open, so this is not a background task. " +
                "Randomised addresses make phones look like a stream of strangers rather " +
                "than the same people staying, which inflates arrivals and departures - " +
                "the fixtures are the trustworthy half. And a step in signal is not " +
                "proof of movement, only of change.",
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
            id = FADING,
            title = "Multipath Fading",
            blurb = "Stand still and watch the signal move anyway.",
            teaches = "Reflections add and cancel, so signal swings several dB with " +
                "nothing moving. This is why signal strength is a poor ruler.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.READY,
            needs = "One transmitter that advertises often - a few packets a second. " +
                "Earbuds, a fitness band or a beacon are ideal; a sensor that speaks once " +
                "a minute is not.",
            howTo = listOf(
                "Pick a source from the list. The rate on the right matters more than " +
                    "the signal strength - two packets a second or better.",
                "Put the phone down and leave it alone for twenty or thirty seconds.",
                "Save the spot, then move the phone about six centimetres - a hand's " +
                    "width - in any direction and record again.",
                "Do that three or four times, then compare. Indoors the spots will " +
                    "disagree by several dB.",
                "For the strongest effect, put a wall or a large metal object between " +
                    "you and the source so no single path dominates.",
            ),
            reading = "Swing is the gap between the best and worst reading. K is how much " +
                "of the signal arrives by one dominant path rather than by reflections, " +
                "in dB: above 10 is a clear line of sight, near 0 means everything has " +
                "bounced. The distance range at the bottom is the same fading fed through " +
                "the formula every proximity feature uses, which is the point of the " +
                "experiment.",
            limits = "It assumes nothing moved while you recorded. A person walking past, " +
                "or the transmitter changing its own power, will look exactly like " +
                "fading. The K factor is a method-of-moments estimate and wants a few " +
                "hundred readings before it settles.",
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
            id = WIFI,
            title = "Wi-Fi Survey",
            blurb = "Every network around you, and what its address gives away.",
            teaches = "An access point announces itself continuously, and the first three " +
                "bytes of its address name whoever registered the block. Hidden networks " +
                "are still perfectly visible - hiding the name hides nothing else.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.READY,
            needs = "Wi-Fi switched on, or Wi-Fi scanning enabled in location settings.",
            howTo = listOf(
                "Open it and leave it. A scan is requested every ten seconds.",
                "Turn off Wi-Fi scan throttling in developer options first, or Android " +
                    "caps you at four scans every two minutes and the picture updates far " +
                    "more slowly than it looks like it should.",
                "Use the Flagged filter for prefixes associated with surveillance " +
                    "hardware, but read the caveat on each - most of them are component " +
                    "vendors rather than the company you are looking for.",
                "Hidden and Open are the other two filters worth a look: a hidden network " +
                    "is not concealed, only unnamed, and an open one is unencrypted.",
            ),
            reading = "Strong means the prefix is registered to the company itself. Weak " +
                "means it is a component vendor whose modules are in a great many " +
                "ordinary devices, and needs corroborating - is it fixed in place, is it " +
                "outdoors, is it still there tomorrow.",
            limits = "Access points only. The probe requests a phone sends out, naming " +
                "networks it has joined before, are client frames and need monitor mode - " +
                "a chipset, a driver and root. No app on a stock phone can capture them.",
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
            id = FARADAY,
            title = "Faraday Cage Test",
            blurb = "How many dB does a tin, a fridge or a crisp packet really block?",
            teaches = "Shielding, apertures, and why the seams matter more than the metal. " +
                "A gap far smaller than the wavelength still leaks, and 2.4 GHz is 12 cm.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.READY,
            needs = "A small Bluetooth device you can put inside something, and something " +
                "to put it in.",
            howTo = listOf(
                "Pick a small transmitter you can physically enclose - earbuds in their " +
                    "case, a tag, a beacon, a spare phone.",
                "It goes in the container, not this phone. You need to keep pressing " +
                    "buttons, and a shielded phone cannot hear anything either.",
                "Measure it in the open first, a pace or two away, without moving either " +
                    "device.",
                "Put it in, close the container properly, and measure again in the same " +
                    "spot.",
            ),
            reading = "The number is how many dB the container took off. Under 3 is " +
                "nothing, 10 to 25 is a real shield with a leaky seam, and no packets at " +
                "all means a complete block that cannot be measured further.",
            limits = "Once nothing gets through there is no way to tell how much further " +
                "it would have gone - infinity here just means more than the phone can " +
                "hear.",
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
            id = EXPLORER,
            title = "Bluetooth Explorer",
            blurb = "Ask a device what it has, and learn what the answers mean.",
            teaches = "An advertisement is a device shouting into the room. A connection " +
                "is a conversation, and devices will tell a stranger far more than most " +
                "people expect - names, makers, model and serial numbers, firmware " +
                "versions - with no pairing at all.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.READY,
            needs = "Something you own, or have permission to poke at. This is the only " +
                "experiment here that transmits.",
            howTo = listOf(
                "Pick a device from the list. It asks before connecting, because unlike " +
                    "everything else in this app the connection is visible to the target.",
                "Watch the commentary as it goes: connecting, discovering services, " +
                    "reading values. Each line says what the word means.",
                "Standard services and values are named from the Bluetooth registry. " +
                    "Custom ones are flagged as custom, which is itself informative.",
                "Try your own earbuds, a fitness band, a smart bulb and a tag - what each " +
                    "hands over to a stranger varies enormously.",
                "Most things that advertise will refuse the connection outright. Beacons " +
                    "and tags broadcast one way and never accept callers.",
            ),
            reading = "Services are groups of related values. Device Information is the " +
                "interesting one - maker, model, serial, firmware - and nothing verifies " +
                "any of it. A serial number is worth noticing: unlike a randomised " +
                "address it never changes, so a device that hands one out is identifiable " +
                "for good.",
            limits = "It only reads, never writes. Many values need a bonded pairing " +
                "first and will simply refuse. A device that accepts the connection and " +
                "then stops answering is normal, and the attempt gives up rather than " +
                "hanging. Connecting is an active act and the other end can see it.",
        ),
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
