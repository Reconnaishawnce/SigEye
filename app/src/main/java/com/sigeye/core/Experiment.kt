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
    /**
     * What it misses, in a sentence or two.
     *
     * Last in the panel and quiet rather than red. It used to be shouted, which made every
     * experiment open with a warning and taught people to skip the whole panel. It still
     * matters; it is not the first thing a fifteen-year-old needs to read.
     */
    val limits: String? = null,
    /**
     * The suite this is a mode of, if it is not a front door of its own.
     *
     * A member keeps its whole registry row - title, walkthrough, reading, limits - and
     * loses only its card on the home screen. That is the point: the explainer a mode
     * shows in its header is read from here by id, so consolidating four experiments into
     * one screen must not mean four descriptions collapsing into one. What somebody can
     * learn about rotation is the same before and after; there is one fewer thing to find
     * on the way in.
     */
    val partOf: String? = null,
) {
    /** Whether this gets a card of its own, or is reached through [partOf]. */
    val standalone: Boolean get() = partOf == null
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
    const val IDENTITY = "identity"
    const val BENCH = "bench"
    const val SWEEP = "sweep"
    const val RTT = "rtt"
    const val SKY = "sky"
    const val WEATHER = "weather"
    const val BLINK = "blink"

    val all: List<Experiment> = listOf(

        // ------------------------------------------------------------- tools
        Experiment(
            id = FOLLOWING,
            partOf = IDENTITY,
            title = "Persistent Tracking",
            blurb = "Keep a device's name attached to it after it changes address.",
            teaches = "Give a device a name and it keeps that name even after it changes its address. " +
                "When it cannot be sure, it says so instead of guessing.",
            category = Experiment.Category.TOOLS,
            status = Experiment.Status.ACTIVE,
            needs = "At least one named device on a list, made in Device Inspector. " +
                "Something has to be scanning: this is a passenger, never a scan of its " +
                "own.",
            howTo = listOf(
                "Name a device in Device Inspector and put it on a list.",
                "Turn that list on here.",
                "Leave something else scanning. This screen is a passenger and never starts the " +
                    "radio itself.",
                "When the device changes address the name follows it, or it tells you it could " +
                    "not be sure.",
            ),
            limits = "Only moves a name when the evidence is strong, so it will lose devices rather " +
                "than guess. A device with a plain, featureless signal cannot be matched at " +
                "all.",
        ),
        Experiment(
            id = INSPECTOR,
            title = "Device Inspector",
            blurb = "Everything broadcasting around you, decoded.",
            teaches = "Everything broadcasting around you, decoded: who made it, what it is saying, " +
                "and roughly how close it is. Name things here and the other experiments can " +
                "use your lists.",
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
            limits = "A name is whatever the device chose to broadcast. Nothing here is verified.",
        ),
        Experiment(
            id = DISCOVERY,
            title = "Discovery",
            blurb = "Learn what is normally here, then watch for what is not.",
            teaches = "Learn what is normally in a place, then watch for anything new. Almost every " +
                "interesting question is about change rather than presence.",
            category = Experiment.Category.TOOLS,
            status = Experiment.Status.ACTIVE,
            needs = "Somewhere to stand still for half a minute while it learns the room.",
            howTo = listOf(
                "Stand somewhere and let it learn what is normally there.",
                "Save that as a snapshot.",
                "Come back later and compare. Anything new is listed first, with an arrow " +
                    "showing whether it is getting closer.",
            ),
            limits = "A phone that has just changed its address looks like a brand new arrival, so " +
                "expect some false arrivals anywhere busy.",
        ),
        Experiment(
            id = RADAR,
            title = "Proximity Radar",
            blurb = "Everything around you, arranged by how close it sounds.",
            teaches = "Everything nearby, arranged by how strong its signal is. Closer to the middle " +
                "means closer to you.",
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
            limits = "Signal strength is a rough guide to distance and says nothing at all about " +
                "direction. A wall or a body moves a blip more than walking does.",
        ),
        Experiment(
            id = CONVOY,
            title = "Traveling Companions",
            blurb = "The devices that turned up in every place you recorded, not just one.",
            teaches = "Record two or three places, then see what turned up in all of them. Anything " +
                "in every one of them came with you rather than living in any of them.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.ACTIVE,
            needs = "Genuinely different places, and enough time between them. File your " +
                "own devices under a list called Mine first.",
            howTo = listOf(
                "Record where you are now, and give it a name.",
                "Go somewhere unrelated and record that too.",
                "Do it once more, somewhere else again.",
                "Anything that turned up in all three came with you.",
            ),
            limits = "Only devices with a fixed address prove anything. A randomized address " +
                "appearing twice is usually two different devices.",
        ),
        Experiment(
            id = WATCHLIST,
            title = "Signal Watch",
            blurb = "Alerts when something you care about comes into range.",
            teaches = "Get told the moment something you care about comes into range. Watch for a " +
                "maker, a device type, or anything on one of your lists.",
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
            limits = "A rule based on an address stops working the moment that device changes it. " +
                "Company and service rules survive.",
        ),

        // ----------------------------------------------------------- sensing
        Experiment(
            id = TRAIN_SPOTTER,
            title = "Train Spotter",
            blurb = "Count a train going past from the phones inside it.",
            teaches = "A passing train is a hundred phones crossing your radio horizon at once, which " +
                "shows up as a sudden spike of devices you have never seen before.",
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
            limits = "Counts devices, not people or carriages. A busy platform can look a lot like a " +
                "train.",
        ),
        Experiment(
            id = SPEED,
            title = "Speed Estimator",
            blurb = "How fast something passed, from how its signal rose and fell.",
            teaches = "A car going past is loudest as it draws level with you. How quickly the signal " +
                "rises and falls gives you its speed.",
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
            limits = "Needs a clean pass with nothing in the way, and your distance to the road is " +
                "an estimate rather than a measurement. Rough passes are marked as such.",
        ),
        Experiment(
            id = CROWD,
            title = "Crowd Counter",
            blurb = "Roughly how many people are around you.",
            teaches = "Roughly how many people are around you, counted from the devices they carry. " +
                "Calibrate it once against a headcount you actually know.",
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
            limits = "People carry different numbers of devices and some carry none. Until you " +
                "calibrate it, treat the number as a trend rather than a count.",
        ),
        Experiment(
            id = FORENSICS,
            title = "Forensics",
            blurb = "Record a place now, so you can ask the question later.",
            teaches = "Record a place, then go through it afterwards. Two hundred devices in three " +
                "minutes is impossible to follow live and easy to review later.",
            category = Experiment.Category.TOOLS,
            status = Experiment.Status.ACTIVE,
            needs = "Somewhere with traffic, and a few minutes.",
            howTo = listOf(
                "Start recording, then carry the phone or leave it somewhere.",
                "Stop when you are done. Nothing leaves the phone.",
                "Go through the timeline afterwards and filter for whatever you care about.",
            ),
            limits = "Only records what was actually advertising at the time. Your own devices are " +
                "left out unless you ask for them.",
        ),
        Experiment(
            id = DWELL,
            title = "Dwell Time",
            blurb = "Who is passing through, and who lives here.",
            teaches = "Tells the people passing through from the things that live here. Under two " +
                "minutes is traffic, over twenty is furniture.",
            category = Experiment.Category.SENSING,
            status = Experiment.Status.ACTIVE,
            howTo = listOf(
                "Leave it running for at least twenty minutes - the classes " +
                    "need time to separate.",
                "Pause the list when you want to tap something.",
                "Tap any device for its full record, and to name, watch or mute " +
                    "it.",
            ),
            limits = "Only fixed hardware can be a resident. Phones change address before they can " +
                "become one, so they always look like traffic.",
        ),
        Experiment(
            id = MOTION,
            title = "RF Motion Detector",
            blurb = "Notices someone crossing a radio path.",
            teaches = "Turn a radio path into a tripwire. Somebody crossing between two devices makes " +
                "the signal jitter long before it gets any weaker.",
            category = Experiment.Category.SENSING,
            status = Experiment.Status.ACTIVE,
            needs = "A few Bluetooth devices sitting still nearby to use as links.",
            howTo = listOf(
                "Put a Bluetooth device on one side of the space and the phone on the other.",
                "Hold still for a minute while it learns what calm looks like.",
                "Walk between them. The score jumps well before the signal gets weaker.",
            ),
            limits = "Tells you something moved, never who or where. Doors, fans and lifts set it " +
                "off too.",
        ),
        Experiment(
            id = PLACE,
            title = "Place Profiler",
            blurb = "How busy a place is hour by hour, from a phone left sitting there.",
            teaches = "Leave the phone somewhere for a few hours and read the shape of the day. A car " +
                "park and a corridor look identical in a snapshot and nothing alike over an " +
                "afternoon.",
            category = Experiment.Category.SENSING,
            status = Experiment.Status.ACTIVE,
            needs = "A few hours, and a charger. The screen has to stay open.",
            howTo = listOf(
                "Leave the phone somewhere it can sit for a few hours, plugged in.",
                "Come back and read the shape of the day.",
                "Flat and full overnight is a car park. Spiky and empty is a corridor.",
            ),
            limits = "Counts devices rather than people, and a phone that changes address gets " +
                "counted twice.",
        ),
        Experiment(
            id = "rhythm",
            title = "Daily Rhythm",
            blurb = "The week where you live, in device counts.",
            teaches = "The week where you live, in device counts. Commute peaks, quiet Sundays, the " +
                "pub emptying.",
            category = Experiment.Category.SENSING,
            status = Experiment.Status.DEVELOPMENT,
            limits = "Counts devices rather than people, and needs several days before the shape " +
                "means anything.",
        ),

        // ----------------------------------------------------------- physics
        Experiment(
            id = BENCH,
            title = "RF Bench",
            blurb = "Measure what radio actually does in your rooms, and keep the numbers.",
            teaches = "Radio behaves differently in every building. Seven quick measurements of what " +
                "your walls, your body and your furniture actually do to a signal, and a " +
                "notebook to keep them in.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.ACTIVE,
            needs = "Bluetooth, a device to measure against, and for some of them a second " +
                "phone or a tape measure. Each one says what it needs when you open it.",
            howTo = listOf(
                "Start with Path loss. The number it gives is what every distance in this app " +
                    "is otherwise assuming.",
                "Name and save each run. What a measurement says on its own matters less than " +
                    "whether it has changed.",
                "The notebook holds every run from all seven in one place.",
            ),
            limits = "A phone is not a calibrated instrument. These are good for comparing readings " +
                "taken on this phone and poor as absolute numbers.",
        ),
        Experiment(
            id = DOPPLER,
            partOf = BENCH,
            title = "Doppler Walk",
            blurb = "Measure how fast a signal fades with distance, by pacing it out.",
            teaches = "Signal strength becomes distance through one formula with one unknown in it. " +
                "Walk away counting your steps and you can measure that number instead of " +
                "guessing it.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.BETA,
            needs = "Something that stays put, a straight line to walk, and ideally a step " +
                "counter - most phones have one.",
            howTo = listOf(
                "Pick something to measure against and stand next to it.",
                "Walk away in a straight line, counting your steps.",
                "Tap at each step. Five or six points is plenty.",
                "The slope of the line is the number you came for.",
            ),
            limits = "Needs a straight, open path. Furniture and walls bend the line, which is " +
                "itself worth seeing.",
        ),
        Experiment(
            id = POLARIZATION,
            partOf = BENCH,
            title = "Rotational Polarization",
            blurb = "How much signal is lost when two antennas stop lining up.",
            teaches = "Radio waves have an orientation. Turn the phone over and the signal can drop " +
                "ten decibels from nothing but a twist of the wrist.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.ACTIVE,
            needs = "One chatty transmitter a few meters away, and an accelerometer - " +
                "which every phone has. No compass, so nothing to calibrate.",
            howTo = listOf(
                "Prop the phone upright with a clear view of the device you are measuring.",
                "Roll it slowly through a full turn without moving it anywhere.",
                "The deepest dip is where the two antennas stopped lining up.",
            ),
            limits = "Only works with a clear line of sight. In a room full of reflections the " +
                "effect fills in and disappears.",
        ),
        Experiment(
            id = CONGESTION,
            title = "Channel Congestion",
            blurb = "Who is using the 2.4 GHz band, and whether Bluetooth has room to shout.",
            teaches = "See who is crowding the 2.4 GHz band and whether Bluetooth can get a word in. " +
                "Bluetooth uses three fixed frequencies picked to dodge Wi-Fi, and a badly " +
                "placed router lands right on top of them.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.ACTIVE,
            needs = "Wi-Fi switched on - being connected is not required, being enabled " +
                "is. Scan throttling off helps the chart keep up.",
            howTo = listOf(
                "Open it anywhere with Wi-Fi around.",
                "Bars show how busy each channel is. The shaded lanes are 1, 6 and 11.",
                "Bluetooth lives in the gaps between those three, so a router parked in a gap " +
                    "lands right on top of it.",
            ),
            limits = "Only measures the networks it can hear. The microwave, the baby monitor and " +
                "the cordless phone next door are all invisible to it.",
        ),
        Experiment(
            id = BANDS,
            partOf = BENCH,
            title = "Wall Penetration",
            blurb = "Measure how much more the building takes from 5 GHz than from 2.4.",
            teaches = "Measure what your building takes out of 5 GHz compared with 2.4. The two bands " +
                "already differ before any wall is involved, so it is how the gap changes as " +
                "you walk that tells you about the building.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.ACTIVE,
            needs = "A dual-band access point broadcasting on both 2.4 and 5 GHz, which " +
                "most routers of the last ten years do, and Wi-Fi switched on.",
            howTo = listOf(
                "Find a router that broadcasts on both 2.4 and 5 GHz.",
                "Measure next to it, with nothing in the way.",
                "Walk to the far side of a wall and measure again.",
                "How much the gap between the bands grew is what the wall cost.",
            ),
            limits = "Needs a router broadcasting on both bands. The two bands are allowed different " +
                "transmit powers, and that difference cannot be separated from the building.",
        ),
        Experiment(
            id = FADING,
            partOf = BENCH,
            title = "Multipath Fading",
            blurb = "How much a signal wanders on its own, with nothing moving.",
            teaches = "Stand perfectly still and watch the signal move anyway. Reflections add and " +
                "cancel, which is why signal strength makes a poor ruler.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.ACTIVE,
            needs = "One transmitter that advertises often - a few packets a second. " +
                "Earbuds, a fitness band or a beacon are ideal; a sensor that speaks once " +
                "a minute is not.",
            howTo = listOf(
                "Prop the phone up and pick a device to watch.",
                "Stand well back and keep the room still.",
                "Wait a minute. Everything you see moving is happening with nothing moving at " +
                    "all.",
            ),
            limits = "Cannot tell a reflection from somebody walking past in the next room. Stand " +
                "still, and keep the room still.",
        ),
        Experiment(
            id = ABSORPTION,
            partOf = BENCH,
            title = "Body Absorption",
            blurb = "How much of the signal your own body blocks, in decibels.",
            teaches = "You are mostly water, and water soaks up 2.4 GHz. Turn slowly in a circle and " +
                "find the shadow you cast in your own radio.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.ACTIVE,
            needs = "A compass. Most phones have one, but it must be calibrated.",
            howTo = listOf(
                "Pick a chatty source. The list shows how many packets a second " +
                    "each one sends - anything under about three will not fill the " +
                    "sectors in time.",
                "Hold the phone flat against your chest, screen facing out. Your torso " +
                    "has to be between the phone and the source.",
                "Turn slowly on the spot, a full circle in about thirty " +
                    "seconds.",
                "Run it at least twice from the same place, facing the same way " +
                    "to start.",
            ),
            limits = "Walls absorb too. A notch much deeper than a few decibels probably has a wall " +
                "in it.",
        ),
        Experiment(
            id = WIFI,
            title = "Wi-Fi Survey",
            blurb = "Every network around you, and what its address gives away.",
            teaches = "Every network in range, and what its name and address give away. Hiding a " +
                "network name hides nothing else about it.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.ACTIVE,
            needs = "Wi-Fi switched on, or Wi-Fi scanning enabled in location settings.",
            howTo = listOf(
                "Open it anywhere. Every network in range is listed.",
                "Tap one for its address, band and channel.",
                "Hidden networks show up too, just without their names.",
            ),
            limits = "An address prefix names whoever registered it, which is often the company that " +
                "made the chip rather than the one that made the product.",
        ),
        Experiment(
            id = FARADAY,
            partOf = BENCH,
            title = "Faraday Cage Test",
            blurb = "What a tin, a fridge or a crisp packet really blocks, in decibels.",
            teaches = "Does a tin, a fridge or a crisp packet actually block anything? Measure it. " +
                "The seams matter more than the metal.",
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
            limits = "A complete block cannot be measured, only reported as complete. Small " +
                "containers change the signal just by being near the phone.",
        ),
        Experiment(
            id = MICROWAVE,
            partOf = BENCH,
            title = "Microwave Interference",
            blurb = "How much a microwave oven costs the 2.4 GHz band, if anything.",
            teaches = "A microwave leaks right into the band Bluetooth uses. Count packets with it " +
                "off, then with it on.",
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
            limits = "Ovens vary enormously. This measures yours, from where you happen to be " +
                "standing.",
        ),
        Experiment(
            id = BLINK,
            title = "Blink",
            blurb = "Send a message between two phones using only silence and its absence.",
            teaches = "Send a message between two phones with no data in any packet at all. Every " +
                "packet is identical and the meaning is carried in the gaps, like a lamp and a " +
                "shutter.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.BETA,
            needs = "Two phones. One transmits and one listens, and some chipsets receive " +
                "Bluetooth perfectly well while refusing to advertise it.",
            howTo = listOf(
                "Open this on both phones and put them a few feet apart.",
                "On one, stay on Send, type a short message and press send.",
                "On the other, switch to Listen. Watch the packet train fill in before any " +
                    "letters appear - the receiver has to find the slot boundaries first.",
                "Read the message as it arrives, a character every few seconds.",
                "Send it again with a shorter slot and watch it start to break.",
            ),
            limits = "About two bits a second, which is slower than morse sent by hand. Some phones " +
                "can receive Bluetooth but will not transmit it.",
        ),
        Experiment(
            id = WEATHER,
            title = "Radio Weather",
            blurb = "How much radio is landing on this phone, and what it is coming from.",
            teaches = "Add up every radio signal landing on this phone. The shape matters more than " +
                "the total, and one cell tower usually outweighs every Wi-Fi and Bluetooth " +
                "device in the building put together.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.BETA,
            howTo = listOf(
                "Stand still and let it settle for half a minute.",
                "Read the split rather than the total. Which radio dominates is the " +
                    "interesting number and it is rarely the one people expect.",
                "Walk into a different sort of place - a basement, a train, a high street - " +
                    "and watch the shape change more than the size.",
                "Turn your own Wi-Fi off and watch nothing happen, because this counts " +
                    "other people's transmitters rather than yours.",
            ),
            limits = "Only counts what this phone has a radio for. Broadcast towers and radar are " +
                "invisible to it, which is why it says busy or quiet and never safe or unsafe.",
        ),
        Experiment(
            id = SKY,
            title = "Satellites Overhead",
            blurb = "Every satellite your phone can hear, and which ones it trusts.",
            teaches = "See every satellite your phone can hear, and which ones it actually trusts. " +
                "This is the faintest signal a phone receives, arriving from twenty thousand " +
                "kilometres up.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.BETA,
            howTo = listOf(
                "Go outside with a clear view of the sky.",
                "Wait a minute or two while the phone finds them.",
                "Filled dots are satellites in the fix. Hollow ones are heard but not trusted.",
            ),
            limits = "Shows what the phone reports. Indoors you will see satellites listed that it " +
                "cannot actually use.",
        ),
        Experiment(
            id = RTT,
            title = "True Ranging",
            blurb = "Distance by timing light, rather than by guessing from loudness.",
            teaches = "Most distances in this app are guessed from loudness. This one times how long " +
                "light took, then shows you how wrong the guess was.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.BETA,
            needs = "802.11mc on this phone and on the access point. Most phones do not " +
                "have it, and most access points do not either - Pixels from the 3 onward " +
                "mostly do, and Google Nest Wifi and a lot of recent mesh kit answer.",
            howTo = listOf(
                "Needs a Wi-Fi router that supports timed ranging. The app checks and tells " +
                    "you.",
                "Stand a known distance away and measure.",
                "Move and repeat two or three times.",
                "It fits the timed distances against the loudness guess and shows you the gap.",
            ),
            limits = "Needs a Wi-Fi router that supports timed ranging, and most do not. The app " +
                "says so rather than guessing.",
        ),
        Experiment(
            id = "linkbudget",
            title = "Two-Phone Link Budget",
            blurb = "Two phones, a known distance, and what the link actually costs.",
            teaches = "Two phones, a known distance, and what the link actually costs. The " +
                "calibration rig for everything else here.",
            category = Experiment.Category.PHYSICS,
            status = Experiment.Status.DEVELOPMENT,
            needs = "A second Android device running SigEye.",
            limits = "Needs two phones and a tape measure. Nothing here works alone.",
        ),

        // ----------------------------------------------------------- mapping
        Experiment(
            id = "deadzones",
            title = "Dead Zone Heatmap",
            blurb = "Walk the building, find where the signal dies.",
            teaches = "Walk a building and map where the signal dies. Shows where the router should " +
                "actually go.",
            category = Experiment.Category.MAPPING,
            status = Experiment.Status.DEVELOPMENT,
            limits = "Indoor position is rough, so the map is a sketch rather than a survey.",
        ),
        Experiment(
            id = "route",
            title = "Route Logger",
            blurb = "Signal strength plotted along a journey.",
            teaches = "Plot signal against a GPS track. Tunnels, stations and dead zones all show up " +
                "as shapes.",
            category = Experiment.Category.MAPPING,
            status = Experiment.Status.DEVELOPMENT,
            limits = "GPS is what fails first in exactly the places worth logging. Tunnels have no " +
                "position at all.",
        ),
        Experiment(
            id = CELLS,
            title = "Cell Handovers",
            blurb = "How often your phone changes tower.",
            teaches = "See how often your phone is handed to a different tower. Standing still it is " +
                "rare, and on a train it happens every minute or two.",
            category = Experiment.Category.MAPPING,
            status = Experiment.Status.ACTIVE,
            needs = "A SIM. Nothing to read in aeroplane mode.",
            howTo = listOf(
                "Open it and leave it while you travel.",
                "Standing still, expect almost no handovers - that is the " +
                    "control.",
                "On a train or in a car they come every minute or two.",
            ),
            limits = "Android reports the serving cell and little else. A handover taken at strong " +
                "signal is load balancing rather than lost coverage.",
        ),

        // ----------------------------------------------------------- privacy
        Experiment(
            id = SWEEP,
            title = "Camera Sweep",
            blurb = "Find license plate cameras as you walk or drive, and note where they are.",
            teaches = "Cameras watching a street are usually not hidden, just unlabelled. Many talk " +
                "over Bluetooth, and one common model broadcasts the serial number printed on " +
                "its own case, so it can be recognized again weeks later.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.BETA,
            needs = "Bluetooth, GPS and somewhere to go. Works on foot, better in a car.",
            howTo = listOf(
                "Tap Start and put the phone down somewhere it can see the sky.",
                "Drive or walk. Anything it recognizes is added with the spot you heard " +
                    "it loudest, which is the closest you got to it.",
                "Tap Export when you are done. You get a spreadsheet, one row per camera.",
            ),
            limits = "Only finds hardware that talks over Bluetooth, and only while it is talking. A " +
                "camera that is hardwired, switched off, or from a maker SigEye does not know " +
                "is invisible here. Finding nothing does not mean there is nothing.",
        ),
        Experiment(
            id = IDENTITY,
            title = "Identity",
            blurb = "Recognize a device after it changes the address meant to hide it.",
            teaches = "Changing a Bluetooth address is meant to stop you being followed, and it " +
                "leaves plenty behind. The shape of a signal, how often it is sent, and how it " +
                "fades as somebody walks away do not change. Four ways of using that.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.ACTIVE,
            needs = "Bluetooth, and for the more involved modes your own phone plus " +
                "somewhere to walk. Every mode says what it needs when you open it.",
            howTo = listOf(
                "Defeat is the one to start with. Pick your own phone and watch what survives " +
                    "an address change.",
                "Lab looks at the whole room instead, timing how often each maker rotates.",
                "Follow is the field exercise, walking with somebody who agreed to it.",
                "A device pinned in one tab stays pinned in all of them.",
            ),
            limits = "A well-built device defeats all of this, and saying so is the point. iPhones " +
                "change their payload at the same moment as their address, which is the right " +
                "way to do it.",
        ),
        Experiment(
            id = FOLLOW,
            partOf = IDENTITY,
            title = "Follow Me",
            blurb = "Narrow a whole street down to the one device traveling with you.",
            teaches = "Walk with somebody who agreed to this and watch a whole street narrow down to " +
                "the one phone that came along. Everything that stayed behind drops out, and " +
                "that is nearly everything.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.BETA,
            needs = "Two people who both agree to this, one of them carrying a phone you " +
                "own, and somewhere to walk. Twenty minutes and a mile is plenty.",
            howTo = listOf(
                "Agree with the other person first. Running this on a stranger is the thing it " +
                    "exists to warn about.",
                "Stand together while it takes a baseline of everything in range.",
                "Walk. Anything that stayed behind drops off the list.",
                "After a mile the list is short. It is a list, never a name.",
            ),
            limits = "Gives you a short list, never a name. Two survivors out of two hundred after " +
                "three miles means something; forty out of two hundred means nothing.",
        ),
        Experiment(
            id = ROTATION_LAB,
            partOf = IDENTITY,
            title = "Rotation Lab",
            blurb = "How often each maker's phones change their Bluetooth address.",
            teaches = "Split a room up by manufacturer and time how often each one changes address. " +
                "Fifteen minutes is a default rather than a rule, and the offset a device keeps " +
                "is itself an identifier.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.ACTIVE,
            needs = "A room with people in it. A cafe, a train, an office - somewhere with " +
                "more than a handful of phones, and time to sit still.",
            howTo = listOf(
                "Open it somewhere busy and leave it running.",
                "The first tab fills straight away. The other two need about twenty minutes.",
                "Devices are grouped by maker, because how often they rotate is a firmware " +
                    "decision.",
            ),
            limits = "Needs a busy room and some patience. The first tab fills immediately and the " +
                "others take a while.",
        ),
        Experiment(
            id = VULNERABILITY,
            title = "Exposure Scan",
            blurb = "What the networks and devices around you admit about their own security.",
            teaches = "Every network and device describes its own security to anyone listening. Old " +
                "encryption, permanent addresses, somebody's name in a device title. You can " +
                "read all of it without connecting to anything.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.ACTIVE,
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
            limits = "Reads what is broadcast. It does not test anything, try any password, or touch " +
                "a network.",
        ),
        Experiment(
            id = EXPLORER,
            title = "Bluetooth Explorer",
            blurb = "Ask a device what it has, and learn what the answers mean.",
            teaches = "An advertisement is a device shouting into a room and a connection is a " +
                "conversation. Ask a device what it can do and it will tell a stranger its " +
                "maker, model, serial and firmware, with no pairing at all.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.ACTIVE,
            needs = "Something you own, or have permission to poke at. This is the only " +
                "experiment here that transmits.",
            howTo = listOf(
                "Pick a device you own. Connecting to it is visible from its end.",
                "It lists everything the device offers.",
                "Device Information is the interesting one: maker, model, serial and firmware.",
            ),
            limits = "Connecting is visible to the device you connect to. Nothing it tells you about " +
                "itself is verified.",
        ),
        Experiment(
            id = BEACONS,
            title = "Beacon Decoder",
            blurb = "Decode the beacon formats hiding in the noise.",
            teaches = "Beacons look like noise until you know the format. iBeacon, Eddystone and " +
                "Apple's Continuity messages are structured data that names itself once " +
                "decoded.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.ACTIVE,
            howTo = listOf(
                "Let it listen. Anything speaking a known format appears.",
                "Use the protocol chips to isolate one format.",
                "Pause before tapping a row - beacons advertise fast and the " +
                    "list reorders.",
            ),
            limits = "Decodes the formats it knows. A custom or encrypted beacon stays a blob.",
        ),
        Experiment(
            id = ROTATION,
            partOf = IDENTITY,
            title = "Defeating Randomization",
            blurb = "Follow one phone through its address changes, and check whether it worked.",
            teaches = "A phone changes its address every quarter of an hour, but the shape of its " +
                "signal and how often it sends do not. That gap is set by firmware, which the " +
                "privacy scheme never touches, and this shows how wide it is.",
            category = Experiment.Category.PRIVACY,
            status = Experiment.Status.ACTIVE,
            needs = "Your own phone, and somewhere you can carry it out of range and back.",
            howTo = listOf(
                "Pick your own phone from the list.",
                "Wait while it learns the fingerprint: how often the phone advertises, and what " +
                    "shape the packet is.",
                "Carry it out of range and back. That proves the app is watching the right " +
                    "thing before it tries anything clever.",
                "Put it down and wait for the address to change. It names the replacement and " +
                    "says exactly why.",
            ),
            limits = "Nothing here is proof. The walk-away test is the only part that is not " +
                "inference, and a link it disproves is the most useful result you can get.",
        ),
    )

    fun byId(id: String): Experiment? = all.firstOrNull { it.id == id }

    /** The modes of a suite, in registry order. Empty for an ordinary experiment. */
    fun membersOf(id: String): List<Experiment> = all.filter { it.partOf == id }

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
    val featuredIds: List<String> =
        listOf(IDENTITY, BENCH, TRAIN_SPOTTER, RADAR, FORENSICS, DISCOVERY)

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
        RTT to "Measure a distance by timing light, and catch the app guessing.",
        SKY to "See every satellite overhead, and which ones your phone actually trusts.",
        WEATHER to "Add up every radio landing on this phone, honestly.",
        BLINK to "Send a sentence between two phones with no data in any packet.",
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
        IDENTITY to "Follow a phone through the address changes meant to stop you.",
        BENCH to "Measure what your walls, your body and your building do to a signal.",
        SWEEP to "Drive around and come back with a list of the cameras watching the street.",
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
                    .filter { it.standalone }
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
