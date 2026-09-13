package com.sigeye.core

/**
 * One entry on the SigEye home screen.
 *
 * Every experiment declares the permissions it needs rather than the app requesting a
 * union of everything up front. A user who only wants the Wi-Fi channel analyzer should
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
    /**
     * How much this experiment has been put through.
     *
     * The distinction that matters is not "finished or not" but "has anyone actually run
     * this on a phone". An experiment can be complete, tested, lint-clean and still wrong,
     * because a unit test cannot tell you whether a sensor reads the axis its documentation
     * claims. Saying so on the card is more honest than shipping everything as equal and
     * letting the user find out.
     */
    enum class Status(val label: String, val badge: String?) {
        /** Run on real hardware, in the real world, and behaved. */
        ACTIVE("Active", null),

        /** Complete and tested, but never yet run anywhere but a build server. */
        BETA("Beta", "beta"),

        /** Described, wanted, not built. */
        DEVELOPMENT("In development", "soon"),
        ;

        /** Whether tapping it opens anything. */
        val openable: Boolean get() = this != DEVELOPMENT
    }

    enum class Category(val label: String, val blurb: String) {
        TOOLS("Tools", "See and name what is around you."),
        SENSING("Counting and sensing", "Who and what is moving past."),
        PHYSICS("RF physics", "How radio actually behaves in your rooms."),
        MAPPING("Mapping", "Survey a building, a route, a band."),
        PRIVACY("What is being broadcast", "What your neighborhood gives away."),
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
    const val FORENSICS = "forensics"
    const val CONVOY = "convoy"
    const val ROTATION = "rotation"
    const val DOPPLER = "doppler"
    // Spelled the old way on purpose, and it has to stay that way. The id is written into
    // the favorites file and into exported CSV headers, so changing it would drop a starred
    // Rotational Polarization on the next launch - sanitise() would see an experiment that
    // no longer exists and quietly remove it. Ids are never shown to anybody.
    const val POLARIZATION = "polarisation"
    const val CONGESTION = "congestion"
    const val BANDS = "bands"
    const val VULNERABILITY = "vulnerability"
    const val FOLLOWING = "following"
    const val ROTATION_LAB = "rotationlab"
    const val FOLLOW = "follow"

    val all: List<Experiment> = listOf(

        // ------------------------------------------------------------- tools
        Experiment(
            id = FOLLOWING,
            title = "Persistent Tracking",
            blurb = "Keep a device's name attached to it after it changes address.",
            teaches = "A nickname is stored against an address, and a phone changes its " +
                "address every quarter of an hour - so the device you named comes back a " +
                "stranger. This follows it across, and refuses loudly whenever it cannot " +
                "be certain.",
            category = Experiment.Category.TOOLS,
            status = Experiment.Status.BETA,
            needs = "At least one named device on a list, made in Device Inspector. " +
                "Something has to be scanning: this is a passenger, never a scan of its " +
                "own.",
            howTo = listOf(
                "Name a device in Device Inspector and put it on a list.",
                "Turn that list on here. Only devices on a followed list are touched.",
                "Leave something scanning. This screen holds the radio while it is open; " +
                    "for an afternoon, start a Forensics or Journey recording instead.",
                "Watch the log. Every move is recorded with the evidence behind it, and " +
                    "every move can be undone.",
                "Check its work. Walk the device out of range and see whether the name " +
                    "goes with it - that is ground truth, and it beats any confidence " +
                    "score.",
            ),
            reading = "The move is made on the same four signals Defeating Randomization " +
                "shows: the advertisement's structure, the advertising interval, the " +
                "signal not jumping across the swap, and the swap's timing. Only the " +
                "strongest rating is acted on, and a device that matches two candidates " +
                "equally well is left alone rather than assigned to the better of them.",
            limits = "Getting this wrong is worse than getting nothing: a name on the " +
                "wrong phone becomes a fact that Signal Watch, Discovery, Forensics and " +
                "Traveling Companions all repeat, and nothing later corrects it. So it " +
                "refuses on plain advertisements, on ambiguity, on anything already " +
                "broadcasting before the swap, and on a device gone more than ten " +
                "minutes. It follows nothing while the app is idle, because it never " +
                "starts a scan.",
        ),
        Experiment(
            id = INSPECTOR,
            title = "Device Inspector",
            blurb = "Everything broadcasting around you, decoded.",
            teaches = "Who made it, what it is saying, how close it is. Name devices and " +
                "group them into lists that other experiments can use.",
            category = Experiment.Category.TOOLS,
            status = Experiment.Status.ACTIVE,
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
            status = Experiment.Status.ACTIVE,
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
                "For the traveling-with-you use: snapshot three genuinely different " +
                    "places and look at what fixed addresses appear in all of them.",
            ),
            reading = "Arrivals are devices unheard of during the baseline. The arrow is " +
                "the signal trend, so a rising one is coming towards you. A comparison of " +
                "two snapshots lists what arrived, what left, and what shifted by more " +
                "than eight dB, which is about where a change stops being multipath.",
            limits = "Randomized addresses defeat most of this and the app says so rather " +
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
            status = Experiment.Status.ACTIVE,
            howTo = listOf(
                "Pick a filter: everything, your watchlist, flagged vendors, or " +
                    "one of your lists.",
                "Pinch to zoom. Zooming narrows the dB range the rings cover, " +
                    "which gives more resolution close to you.",
                "Tap a blip to select it and see its signal, range and trend.",
                "Tap Locate to hunt it down on foot.",
            ),
            reading = "Distance from the center is signal strength, strongest in the " +
                "middle. A blip drifting inward is getting closer.",
            limits = "The angle is not a direction. One antenna cannot measure a " +
                "bearing, so the angle is only a hash of the address that keeps " +
                "each device in its own spot. Never read the radar as a map.",
        ),
        Experiment(
            id = CONVOY,
            title = "Traveling Companions",
            blurb = "Record here, then there, then somewhere else. What appears in all of it?",
            teaches = "Anything present in three unrelated places was traveling with you " +
                "rather than living in any of them. The idea is simple; being honest " +
                "about how weak it is takes most of the work.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.ACTIVE,
            needs = "Genuinely different places, and enough time between them. File your " +
                "own devices under a list called Mine first.",
            howTo = listOf(
                "In Device Inspector, put your own earbuds, watch and car on a list " +
                    "called Mine. They follow you perfectly, and without this the results " +
                    "are forty rows of your own belongings.",
                "Start a leg where you are and give it a few minutes, so everything nearby " +
                    "has a chance to advertise at least once.",
                "Finish the leg, travel somewhere genuinely different, and start another.",
                "Three legs over an hour is the point at which the answer starts to mean " +
                    "something. It will tell you when it is not there yet.",
                "Tap any candidate for the reasoning, and mark it as yours if it is.",
            ),
            reading = "Something in every leg with a fixed address, over a real span of " +
                "time, is the only thing rated strongly. Anything randomized is capped at " +
                "the weakest rating however often it appears, because the same random " +
                "address turning up repeatedly means either it is not rotating or the " +
                "legs were too close together, and nothing here can tell those apart.",
            limits = "This cannot catch a phone that rotates its address, which is most " +
                "phones - it catches fitted equipment, tyre sensors and cheap trackers. " +
                "Legs recorded close together in time and place share their contents by " +
                "accident, so short journeys produce long innocent lists, and the app " +
                "refuses to conclude anything until there is real separation.",
        ),
        Experiment(
            id = WATCHLIST,
            title = "Signal Watch",
            blurb = "Alerts when something you care about comes into range.",
            teaches = "Which identifiers survive address randomization and which do not. " +
                "Manufacturer prefixes and service data outlive a rotating MAC.",
            category = Experiment.Category.TOOLS,
            status = Experiment.Status.ACTIVE,
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
                "survive address randomization; addresses do not.",
        ),

        // ----------------------------------------------------------- sensing
        Experiment(
            id = TRAIN_SPOTTER,
            title = "Train Spotter",
            blurb = "Counts new Bluetooth devices nearby and flags the bursts.",
            teaches = "A passing train is a hundred phones crossing your radio horizon at " +
                "once. That shows up as a spike in previously unseen addresses.",
            category = Experiment.Category.SENSING,
            status = Experiment.Status.ACTIVE,
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
            status = Experiment.Status.ACTIVE,
            needs = "A known perpendicular distance to the road or track.",
            howTo = listOf(
                "Measure or pace the distance from where the phone sits to the middle of " +
                    "the track, and set it. Everything scales directly off this number.",
                "Press Watch for passes and leave it.",
                "Results appear a few seconds after each vehicle has gone - a pass is only " +
                    "recognizable once it is over.",
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
            status = Experiment.Status.ACTIVE,
            howTo = listOf(
                "Set the signal floor to define what counts as here - around " +
                    "-70 dBm is a room, -85 is a building.",
                "Stand with a group you have actually counted.",
                "Adjust devices per person until the estimate matches the real " +
                    "number.",
                "That figure is now roughly right for this kind of place.",
            ),
            reading = "The radar shows what is present now, strongest at the center. " +
                "The headline uses only devices heard in the last minute.",
            limits = "It counts devices, not people. Laptops, televisions and " +
                "printers belong to nobody, and one person can carry three " +
                "advertisers.",
        ),
        Experiment(
            id = FORENSICS,
            title = "Forensics",
            blurb = "Record everything, then work out afterwards what mattered.",
            teaches = "Two hundred devices go past in three minutes and the one you care " +
                "about is indistinguishable in the moment. Recording first and filtering " +
                "afterwards turns an impossible live problem into an easy review.",
            category = Experiment.Category.TOOLS,
            status = Experiment.Status.ACTIVE,
            needs = "Somewhere with traffic, and a few minutes.",
            howTo = listOf(
                "Start recording and leave it for as long as the thing you are after " +
                    "might take to appear - three minutes for a street.",
                "Stop, then subtract. Hide unchanged removes everything that sat there " +
                    "doing nothing, which is usually most of it.",
                "Hide known removes anything you have nicknamed or filed, so what is left " +
                    "is what you have never named.",
                "Went past only keeps the things that rose to a peak and fell away again " +
                    "- the shape of a vehicle rather than a shop.",
                "Tap a row for the full detail: vendor, swing, when it was audible, and " +
                    "where its peak fell within its own visit.",
            ),
            reading = "The bar on each row is the whole recording; the filled part is " +
                "when that device was audible and the line inside it is its signal. " +
                "Something that arrived halfway through and swelled in the middle is a " +
                "pass. Something flat from edge to edge is furniture.",
            limits = "Randomized addresses mean one phone driving past can appear as two " +
                "or three separate devices. The recording lives in memory and is lost " +
                "when you leave the screen unless you export it first.",
        ),
        Experiment(
            id = DWELL,
            title = "Dwell Time",
            blurb = "Who is passing through, and who lives here.",
            teaches = "Sort devices by how long they stay. Under two minutes is traffic, " +
                "over twenty is a fixture - and only the fixtures are trustworthy, " +
                "because a phone changes address before it can become one.",
            category = Experiment.Category.SENSING,
            status = Experiment.Status.ACTIVE,
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
            limits = "Passing counts are inflated by address randomization: one " +
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
            status = Experiment.Status.ACTIVE,
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
                "own calm behavior, in standard deviations. Level and jitter are shown " +
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
            status = Experiment.Status.ACTIVE,
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
                "Randomized addresses make phones look like a stream of strangers rather " +
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
            status = Experiment.Status.DEVELOPMENT,
        ),

        // ----------------------------------------------------------- physics
        Experiment(
            id = DOPPLER,
            title = "Doppler Walk",
            blurb = "Walk away counting your steps, and measure the number everything guesses.",
            teaches = "Signal becomes distance through one formula with one unknown - the " +
                "path loss exponent. Every proximity feature ever shipped guesses it. " +
                "With a real distance from the step counter it can be measured instead.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.BETA,
            needs = "Something that stays put, a straight line to walk, and ideally a step " +
                "counter - most phones have one.",
            howTo = listOf(
                "Set your stride length. About 0.415 times your height is the usual " +
                    "estimate, but pacing out a known distance and dividing is better - " +
                    "an error here goes straight into the answer.",
                "Pick something fixed and leave it where it is. A beacon, a speaker, a " +
                    "television.",
                "Walk steadily away in a straight line. Fifteen meters is plenty.",
                "Do not turn back before finishing. The fit reads a return trip as the " +
                    "signal refusing to fall, and says so rather than reporting nonsense.",
                "Try it again in a corridor, then through a wall. The number moves a long " +
                    "way, which is the point.",
            ),
            reading = "The model is rssi = reference - 10 n log10(distance), so signal " +
                "against the logarithm of distance is a straight line of slope -10n. Two " +
                "is free space, three or so is a normal room, four is several walls, and " +
                "below two means a corridor guiding the wave. Scatter about the line is " +
                "multipath.",
            limits = "The distance is only as good as the stride length, and the step " +
                "counter needs physical activity permission or it returns nothing at all. " +
                "A walk that curves, or one where the transmitter is not actually fixed, " +
                "measures something other than path loss. Under a few meters of travel it " +
                "declines to fit.",
        ),
        Experiment(
            id = POLARIZATION,
            title = "Rotational Polarization",
            blurb = "Roll the phone over and watch the signal die.",
            teaches = "Radio has an orientation. Turn a receiving antenna across the " +
                "field and it stops hearing - ten to twenty dB from nothing but a twist " +
                "of the wrist, and the depth of that null says how much of the signal " +
                "reached you without bouncing off anything.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.BETA,
            needs = "One chatty transmitter a few meters away, and an accelerometer - " +
                "which every phone has. No compass, so nothing to calibrate.",
            howTo = listOf(
                "Pick a source that talks at least twice a second, a few meters away " +
                    "with a clear path. Too close and reflections fill the null in.",
                "Hold the phone with its long axis level, pointing left and right in " +
                    "front of you. Stood on end, gravity runs down that axis and there " +
                    "is nothing left to measure roll against.",
                "Roll it slowly about that axis, like turning a rolling pin - screen up, " +
                    "screen sideways, screen down, all the way over and back. Keep it in " +
                    "the same spot while you do.",
                "Watch the plot go oval. The long way is aligned, the short way crossed.",
                "Try it again on a different source. A phone tumbling in a pocket has no " +
                    "fixed orientation to align with and gives almost nothing.",
            ),
            reading = "Depth is the gap between the best and worst roll angle. Six dB or " +
                "more, with the two extremes about ninety degrees apart, is polarization " +
                "and nothing else in a room produces that shape. The deeper the null, the " +
                "more of the signal arrived by one clean path - the same quantity " +
                "Multipath Fading calls K, measured a completely different way.",
            limits = "Stood on end the measurement is undefined and readings are dropped " +
                "rather than plotted. Close in, or through walls, the null fills with " +
                "reflections and the answer is honestly small rather than wrong. Moving " +
                "the phone while rolling it measures distance instead. Some antennas are " +
                "circularly polarized and simply have no null to find.",
        ),
        Experiment(
            id = CONGESTION,
            title = "Channel Congestion",
            blurb = "Who is using the 2.4 GHz band, and whether Bluetooth has room to shout.",
            teaches = "Bluetooth advertises on three fixed frequencies chosen to dodge " +
                "Wi-Fi 1, 6 and 11. An access point parked anywhere else lands on them, " +
                "and the packets stop arriving. This shows both halves at once.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.BETA,
            needs = "Wi-Fi switched on - being connected is not required, being enabled " +
                "is. Scan throttling off helps the chart keep up.",
            howTo = listOf(
                "Open it and wait for the first Wi-Fi scan. The chart is drawn against " +
                    "frequency rather than channel number, so the overlaps are visible.",
                "Find the three tertiary markers. Those are Bluetooth advertising " +
                    "channels 37, 38 and 39 - dashed when clear, solid when something is " +
                    "sitting on them.",
                "Look at what is arriving underneath. Each device's own advertising " +
                    "interval says how often it transmits; the percentage is how much of " +
                    "that reached the phone.",
                "Walk to a different part of the building and watch both halves move " +
                    "together. A router on channel 3 in the next room is visible in the " +
                    "chart and audible in the percentages.",
            ),
            reading = "Bars are summed neighbor power on each 20 MHz channel, added as " +
                "power rather than as decibels - two equal signals are 3 dB together, not " +
                "twice the number. Shaded lanes are the non-overlapping 1, 6 and 11. A " +
                "reception figure well under half means packets are being lost; compared " +
                "against the best link in view, it rules out the phone itself.",
            limits = "A phone cannot measure airtime, only who is present and how loud, " +
                "so a single loud access point with no traffic reads as congestion. " +
                "Android never says which advertising channel a packet arrived on, so " +
                "reception is measured per device rather than per channel. Bluetooth " +
                "Classic, microwaves, video senders and cordless phones are all in this " +
                "band and none of them appear in a Wi-Fi scan.",
        ),
        Experiment(
            id = BANDS,
            title = "Wall Penetration",
            blurb = "Measure how much more the building takes from 5 GHz than from 2.4.",
            teaches = "Everyone has heard that 5 GHz does not go through walls as well. " +
                "The two radios of one router already differ before any wall is involved, " +
                "so the measurement is not the gap - it is how the gap changes when you " +
                "walk. What is left is the building.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.BETA,
            needs = "A dual-band access point broadcasting on both 2.4 and 5 GHz, which " +
                "most routers of the last ten years do, and Wi-Fi switched on.",
            howTo = listOf(
                "Stand where you can see the router, with nothing between you and it. " +
                    "Wait for the pairs to settle - three scans each.",
                "Set the baseline. That records what the two bands look like with no " +
                    "building in the way, which is everything that is not the answer.",
                "Walk. Another room, the next floor, the far end of the garden.",
                "Read the excess. That is how much more the walls took from 5 GHz than " +
                    "from 2.4 - the one number the phrase everybody repeats actually " +
                    "refers to.",
                "Save a spot in each room, and you have surveyed the building.",
            ),
            reading = "An antenna's effective area falls with the square of frequency, so " +
                "5 GHz starts about 6.6 dB down on 2.4 in open air with nothing in the " +
                "way. On top of that the regulations permit different transmit powers per " +
                "band, the vendor may have chosen differently again, and the phone's two " +
                "receive chains are not equally sensitive - all constant, all cancelled by " +
                "the baseline. Under 3 dB of excess is scan noise; over 10 dB is " +
                "structural; and a negative number means 2.4 GHz is being interfered with " +
                "rather than 5 GHz being blocked.",
            limits = "Pairs are matched by BSSID where two radios differ only in the last " +
                "octet, which is one box saying so, and otherwise by network name and " +
                "vendor - which in a building full of mesh nodes can pair two different " +
                "boxes, and the screen marks those. Android's RSSI is uncalibrated and a " +
                "single scan wanders several dB, so readings are averaged over several. " +
                "Moving the baseline invalidates every saved spot, so it clears them.",
        ),
        Experiment(
            id = FADING,
            title = "Multipath Fading",
            blurb = "Stand still and watch the signal move anyway.",
            teaches = "Reflections add and cancel, so signal swings several dB with " +
                "nothing moving. This is why signal strength is a poor ruler.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.ACTIVE,
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
            status = Experiment.Status.ACTIVE,
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
            status = Experiment.Status.ACTIVE,
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
            id = FARADAY,
            title = "Faraday Cage Test",
            blurb = "How many dB does a tin, a fridge or a crisp packet really block?",
            teaches = "Shielding, apertures, and why the seams matter more than the metal. " +
                "A gap far smaller than the wavelength still leaks, and 2.4 GHz is 12 cm.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.ACTIVE,
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
            blurb = "Watch a microwave flatten the 2.4 GHz band.",
            teaches = "A consumer microwave leaks around 2.45 GHz, right in the middle of " +
                "the band Bluetooth uses. Measure how many advertisements survive with " +
                "it off, then with it on.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.ACTIVE,
            needs = "A microwave and something to heat in it.",
            howTo = listOf(
                "Stand near the microwave with a Bluetooth device in the room, " +
                    "and put a cup of water in the microwave so it has something to " +
                    "heat.",
                "Record the baseline with the microwave off, for thirty seconds.",
                "Start the microwave, then record the test phase for thirty seconds.",
                "Stop, and compare.",
            ),
            reading = "The measure is how many advertisements a second reach the " +
                "phone. A microwave leaking into the band drowns them out, so " +
                "the rate falls and the surviving packets read weaker.",
            limits = "It cannot see the microwave directly, only the damage it does. A " +
                "modern, well-sealed one may show almost nothing, which is itself the " +
                "answer: your kitchen Wi-Fi problem is somewhere else.",
        ),
        Experiment(
            id = "rtt",
            title = "True Ranging (Wi-Fi RTT)",
            blurb = "Distance by timing light, not by guessing from loudness.",
            teaches = "802.11mc measures time of flight in centimetres. Put it beside an " +
                "RSSI estimate and the gap is the whole lesson.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.DEVELOPMENT,
            needs = "802.11mc support on both this phone and the access point.",
        ),
        Experiment(
            id = "linkbudget",
            title = "Two-Phone Link Budget",
            blurb = "Control both ends and watch the numbers line up.",
            teaches = "Step the transmit power and the received signal tracks it one for " +
                "one. The calibration rig for every other experiment here.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.DEVELOPMENT,
            needs = "A second Android device running SigEye.",
        ),

        // ----------------------------------------------------------- mapping
        Experiment(
            id = "deadzones",
            title = "Dead Zone Heatmap",
            blurb = "Walk the building, find where the signal dies.",
            teaches = "Where the router should actually go, and how much one wall costs.",
            category = Experiment.Category.MAPPING,
            status = Experiment.Status.DEVELOPMENT,
        ),
        Experiment(
            id = "route",
            title = "Route Logger",
            blurb = "Signal density along a journey.",
            teaches = "Tunnels, stations, dead zones and platform geometry, plotted " +
                "against a GPS track.",
            category = Experiment.Category.MAPPING,
            status = Experiment.Status.DEVELOPMENT,
        ),
        Experiment(
            id = CELLS,
            title = "Cell Handovers",
            blurb = "How often your phone changes tower.",
            teaches = "Standing still, handovers are rare. On a train they come every " +
                "minute or two, and a handover taken while signal was still strong is " +
                "load balancing rather than lost coverage.",
            category = Experiment.Category.MAPPING,
            status = Experiment.Status.ACTIVE,
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
            id = FOLLOW,
            title = "Follow Me",
            blurb = "Narrow a whole street down to the one device traveling with you.",
            teaches = "Following somebody by their phone needs no prior knowledge of it " +
                "and no special hardware. It is done by elimination: walk together and " +
                "everything that stayed behind drops out, which is nearly everything.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.BETA,
            needs = "Two people who both agree to this, one of them carrying a phone you " +
                "own, and somewhere to walk. Twenty minutes and a mile is plenty.",
            howTo = listOf(
                "Agree with the other person first. This is a demonstration of following " +
                    "somebody, and running it on a stranger is the thing it exists to " +
                    "warn about.",
                "Start a standing leg where you meet. It cuts whatever walks past while " +
                    "you are both still.",
                "Start a moving leg and travel together. This is the one that does the " +
                    "work - the shops, the parked cars and the other passengers all fall " +
                    "out of range and out of the list.",
                "Do that two or three times. Watch the survivor count against the number " +
                    "of devices heard: that ratio is the whole finding.",
                "Lock onto the survivor and let it rotate. If it changes address it will " +
                    "be picked up again only when the evidence is unambiguous.",
                "Check it by walking apart. If the thing you locked onto fades as the " +
                    "other person leaves, you had the right one.",
            ),
            reading = "The honest answer is the short list and its denominator, never a " +
                "name. Two survivors out of two hundred after three miles is a strong " +
                "claim; forty out of two hundred after standing in a lobby is no claim at " +
                "all. Moving legs count for far more than standing ones, because staying " +
                "in range across a mile of city is something very few things can do.",
            limits = "A device only survives a leg if it was heard during it, so a tunnel " +
                "or a pocket costs you the target. Rotation is handled by the same " +
                "machinery as Persistent Tracking and refuses just as readily - two " +
                "equally good candidates stop it rather than one being picked. And a " +
                "survivor is a hypothesis: the other person walking away from you is the " +
                "only real test, which is why the walkthrough ends with it.",
        ),
        Experiment(
            id = ROTATION_LAB,
            title = "Rotation Lab",
            blurb = "A whole room's address privacy, grouped by maker and measured by clock.",
            teaches = "Rotation is a firmware decision, so it is a vendor trait - a " +
                "maker's whole fleet behaves alike. And the fifteen minutes everyone " +
                "quotes is a default, not a rule: the timeout is settable from a second " +
                "to an hour, it runs from the last change rather than from a clock, and " +
                "that free-running offset is an identifier every rotation preserves.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.BETA,
            needs = "A room with people in it. A cafe, a train, an office - somewhere with " +
                "more than a handful of phones, and time to sit still.",
            howTo = listOf(
                "Open it somewhere busy and leave it running. The first tab fills " +
                    "immediately; the other two need patience.",
                "Read the cohorts. Each maker's bar is how much of their fleet bothers " +
                    "with a private address, and a bar that is entirely red is a fleet " +
                    "anybody with a phone can follow indefinitely.",
                "Wait for the middle tab. A period is the gap between two address " +
                    "changes, so a device has to be followed through three addresses " +
                    "before there is one - about half an hour on the usual timer.",
                "Use the third tab to check the work. Put two or three tracks on one " +
                    "chart and look at what the signal does at each marker.",
                "Export when you have something. The CSV carries every track, its " +
                    "measured period and its phase.",
            ),
            reading = "A phase is where in the cycle a device changes, measured against " +
                "the epoch rather than a clock. Two phones on the same nine hundred " +
                "second timer land on different phases and keep them, which makes the " +
                "phase a handle that survives the thing designed to remove handles - " +
                "until Bluetooth is toggled, flight mode is used, or the device reboots. " +
                "Worth one slot in twenty on a fifteen minute cycle: useful next to other " +
                "evidence, useless on its own.",
            limits = "Counting manufacturers is safe, because a company identifier is a " +
                "fact in the payload about a product. Linking two addresses into a track " +
                "is an inference about a stranger, so only the strongest rating is drawn " +
                "at all and a track remains a hypothesis - Defeating Randomization is " +
                "where you test one, by walking a device you own out of range. A vendor " +
                "read from an address prefix means nothing once the address is random, " +
                "which is why much of any room stays unidentified.",
        ),
        Experiment(
            id = VULNERABILITY,
            title = "Exposure Scan",
            blurb = "Insecure and over-sharing things in range, from what they broadcast.",
            teaches = "Every network and every Bluetooth device describes its own security " +
                "in the beacons it sends to everyone. WPS, WEP, missing frame protection, " +
                "a permanent address, a person's name - all of it is readable without " +
                "connecting to anything.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.BETA,
            needs = "Wi-Fi switched on to scan, which does not require being connected.",
            howTo = listOf(
                "Open it and let both scans run. Findings are grouped by device, worst " +
                    "first.",
                "Tap a device for what each finding means and what to change.",
                "Start with your own network and your own devices. Most of what a scan " +
                    "picks up belongs to neighbors and is not yours to change.",
                "Use the neighbors as calibration. Once you know how common WPS and " +
                    "missing frame protection are, your own network stops being a guess.",
            ),
            reading = "Critical and high are worth acting on today; medium is a decision " +
                "worth making deliberately rather than by accident; low is hygiene. A " +
                "finding marked good is there because getting it right deserves saying " +
                "so - an open network running OWE is not the same as an open network.",
            limits = "No CVEs are named. Matching a real vulnerability needs a model and " +
                "a firmware version and neither is in a beacon, so a scanner that infers " +
                "one from a vendor name is producing fiction that looks like a finding. " +
                "Nothing is connected to either: whether a Bluetooth device accepts an " +
                "unauthenticated connection cannot be established without making one, " +
                "which is an active act against equipment that is probably not yours.",
        ),
        Experiment(
            id = EXPLORER,
            title = "Bluetooth Explorer",
            blurb = "Ask a device what it has, and learn what the answers mean.",
            teaches = "An advertisement is a device shouting into the room. A connection " +
                "is a conversation, and devices will tell a stranger far more than most " +
                "people expect - names, makers, model and serial numbers, firmware " +
                "versions - with no pairing at all.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.ACTIVE,
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
                "any of it. A serial number is worth noticing: unlike a randomized " +
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
            status = Experiment.Status.ACTIVE,
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
            id = ROTATION,
            title = "Defeating Randomization",
            blurb = "Follow one phone through its address changes, and check whether it worked.",
            teaches = "An address changes every quarter of an hour, but the shape of the " +
                "advertisement and the rate it is sent at do not - those are set by " +
                "firmware, which the privacy scheme never touches. That is the gap, and " +
                "this is how wide it actually is.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.ACTIVE,
            needs = "Your own phone, and somewhere you can carry it out of range and back.",
            howTo = listOf(
                "Pick your own phone from the list. A randomized address is the point - a " +
                    "fixed one has nothing to defeat.",
                "Wait while its fingerprint is learned: how often it advertises, and what " +
                    "shape the advertisement is.",
                "Run a walk-away test. Carry it out of the room and watch the signal " +
                    "collapse - that proves the app is watching the right thing before " +
                    "anything clever is attempted.",
                "Leave it alone and wait for the address to change. Many phones only " +
                    "rotate with the screen off, so put it down and be patient.",
                "When it rotates, the app names the replacement and says exactly why. " +
                    "Then walk away again - if the new address fades too, the link held.",
            ),
            reading = "Four signals, none conclusive alone: the advertisement structure, " +
                "the advertising interval, signal continuity across the swap, and the " +
                "handover timing. The confidence is a sum of those. The walk-away test is " +
                "the only part that is not inference, and a link it disproves is the most " +
                "useful thing the experiment can produce.",
            limits = "iOS is the hardest case, because it rotates the payload alongside " +
                "the address on the same schedule - which is the correct way to implement " +
                "this. A plain advertisement carrying nothing distinctive cannot be " +
                "matched on at all and the app says so rather than guessing. Nothing here " +
                "is proof, which is exactly why the walk-away test exists.",
        ),
    )

    fun byId(id: String): Experiment? = all.firstOrNull { it.id == id }

    /**
     * What a new install starts with starred.
     *
     * Twenty-seven experiments across five categories is a good library and a bad front
     * door - someone arriving for the first time has no way to tell which of them is the
     * one that will make them care. These five produce a result in under a minute and are
     * the ones a person would show someone else, so they seed the favorites list.
     *
     * Only a starting point. The list belongs to the user from the first tap of a star,
     * and is never re-seeded afterwards - see [FavoriteStore]. Starred experiments still
     * appear in their own categories below, because a shortcut that removes things from
     * where they belong is a maze.
     */
    val featuredIds: List<String> = listOf(ROTATION, TRAIN_SPOTTER, RADAR, FORENSICS, DISCOVERY)

    /** Why each featured experiment earned the spot, in a few words. */
    /**
     * One line per experiment, for the favorites card.
     *
     * Written in the second person and aimed at somebody deciding whether to tap, which is
     * a different job from [Experiment.blurb] - the blurb describes the experiment, this
     * sells the next sixty seconds. Every ready experiment has one, because five good ones
     * and twenty-three fallbacks was visible the moment anyone starred a sixth thing.
     */
    val featuredReasons: Map<String, String> = mapOf(
        ROTATION to "Follow a phone across the address changes meant to stop you.",
        TRAIN_SPOTTER to "Count carriages going past from the signals inside them.",
        RADAR to "Everything around you, at the range it is at, live.",
        FORENSICS to "Record a place, then go through what was there afterwards.",
        DISCOVERY to "Learn what is normally here, then watch for what is not.",
        ROTATION_LAB to "Split a room by manufacturer and time how often each one rotates.",
        FOLLOWING to "Keep a name on a device after it changes its address.",
        INSPECTOR to "Open up anything nearby and read what it is shouting.",
        WATCHLIST to "Get told the moment something you care about turns up.",
        CONVOY to "Record two places and find what was in both of them.",
        VULNERABILITY to "Find out what the networks around you are admitting about themselves.",
        EXPLORER to "Ask a device what it can do, and understand the answer.",
        BEACONS to "Decode the beacon formats hiding in the noise around you.",
        WIFI to "Every network in range, and what its name gives away.",
        SPEED to "Clock a passing car from the shape of its signal.",
        CROWD to "Put a number on how many people are around you.",
        DWELL to "Tell the people passing through from the people who live here.",
        MOTION to "Turn a radio path into a tripwire and watch someone cross it.",
        PLACE to "Leave the phone somewhere for hours and read the shape of the day.",
        DOPPLER to "Walk away counting steps and measure the number everyone else guesses.",
        POLARIZATION to "Turn the phone over and watch the signal die.",
        CONGESTION to "See who is crowding the band and whether Bluetooth can get a word in.",
        BANDS to "Measure exactly what your walls take out of 5 GHz.",
        FADING to "Stand perfectly still and watch the signal move anyway.",
        ABSORPTION to "Turn in a circle and find the shadow your own body casts.",
        FARADAY to "Settle the argument about whether a crisp packet blocks anything.",
        MICROWAVE to "Watch a microwave flatten the band your Wi-Fi is on.",
        CELLS to "See how often your phone hands you to a different tower.",
        FOLLOW to "Narrow a street down to the one phone that is coming with you.",
    )

    /**
     * Each category's experiments, proven ones first.
     *
     * Tier orders within a category rather than replacing it. Someone looking for the
     * microwave experiment is thinking about what it does, not about how well tested it
     * is - so the categories stay, and the tier decides where a card sits inside one and
     * what badge it carries.
     */
    fun byCategory(
        includeUnbuilt: Boolean = true,
    ): List<Pair<Experiment.Category, List<Experiment>>> =
        Experiment.Category.entries
            .map { category ->
                category to all
                    .filter { it.category == category }
                    .filter { includeUnbuilt || it.status.openable }
                    .sortedBy { it.status.ordinal }
            }
            .filter { it.second.isNotEmpty() }

    /** The wish list: described, wanted, not built. */
    fun unbuilt(): List<Experiment> = all.filter { !it.status.openable }

    /**
     * Experiments matching a search.
     *
     * Over the title, the blurb and what it teaches, because somebody looking for "walls"
     * will not think of the word "penetration" and somebody looking for "tracking" does
     * not know it is filed under privacy. Working experiments first - a search that offers
     * an unbuilt one above a built one is offering a dead end.
     */
    fun search(query: String): List<Experiment> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return all
            .filter { experiment ->
                experiment.title.lowercase().contains(needle) ||
                    experiment.blurb.lowercase().contains(needle) ||
                    experiment.teaches.lowercase().contains(needle)
            }
            .sortedWith(
                compareBy<Experiment> { it.status.ordinal }
                    .thenBy { !it.title.lowercase().startsWith(needle) }
                    .thenBy { it.title },
            )
    }

    fun count(status: Experiment.Status): Int = all.count { it.status == status }

    /** Everything that opens: active and beta together. */
    val readyCount: Int get() = all.count { it.status.openable }
}
