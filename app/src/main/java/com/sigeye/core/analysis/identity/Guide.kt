package com.sigeye.core.analysis.identity

/** Where a follow has got to, and therefore what is normal right now. */
enum class Stage(val label: String) {
    /** The first minute or two, where nothing can have dropped yet. */
    SETTLING("Settling"),

    /** The collapse. Everything you walked past is going out of range. */
    THINNING("Thinning"),

    /** Few enough to read, too many to be an answer. */
    SHORTLIST("Short list"),

    /** Down to a pocket. */
    IDENTIFY("Identify"),

    /** Not narrowing, and elimination alone is not going to finish it. */
    STALLED("Stalled"),
}

/** The one thing to do next. */
enum class Move(val label: String) {
    WAIT("Keep walking"),
    WALK("Keep walking"),
    GET_MOVING("Start walking"),
    WALK_BY("Overtake them"),
    ORBIT("Circle them"),
    HOLD("Pick one"),
    OWN_KIT("Rule out your own"),
}

/**
 * What is happening, what to expect, and the single next thing to do.
 *
 * [expect] is the part that was missing and it is most of why this felt broken. A big
 * number that does not move reads as a stalled app, and for the first ninety seconds of
 * every follow the number genuinely cannot move: nothing has been silent long enough to be
 * retired yet. Saying so turns a screen that looks stuck into one that is visibly working.
 */
data class Guidance(
    val stage: Stage,
    val headline: String,
    val expect: String,
    val move: Move,
    val why: String,
    /** True when this is the moment to stop reading and do something. */
    val urgent: Boolean = false,
)

/**
 * Telling somebody holding a phone on a street what to do with it.
 *
 * The app knew everything in here and said none of it. It showed a number and a wall of
 * cards, and left the operator to work out from first principles whether a still number
 * meant progress, whether to walk further, or whether the thing had simply stopped.
 *
 * The shape of a follow is not a mystery. Elimination collapses the list quickly while you
 * walk away from everything static, and then it stops: what survives ten minutes is other
 * people going the same way at the same speed, and no amount of further walking separates
 * them. Only a walk-by does. Knowing that, the app can say it rather than waiting to be
 * asked.
 *
 * Pure and Android-free.
 */
object Guide {

    /** Before this, nothing has been silent for a full drop-off, so nothing can have gone. */
    const val SETTLE_MS = 90_000L

    /** How far back to look when asking whether the count is still falling. */
    const val STALL_WINDOW_MS = 4 * 60_000L

    /** A fall smaller than this fraction over that window is not progress. */
    const val STALL_FRACTION = 0.12

    /** Standing still this long means the method has stopped working, not the app. */
    const val STILL_MS = 2 * 60_000L

    /**
     * Above this, elimination is still the right tool. At or below it, walking further is
     * mostly not going to help and the probes are what is left.
     */
    const val PROBE_FROM = 12

    fun of(
        state: FollowState,
        counts: List<Moment.Count>,
        /** How long the phone has been still, or null when nothing can tell. */
        stillForMs: Long?,
    ): Guidance? {
        if (state.followStartedAtMs == null) return null

        val left = state.stillIn.size
        val elapsed = state.listeningForMs
        val standing = stillForMs != null && stillForMs >= STILL_MS

        // Standing still beats everything else, because it is the one situation where the
        // method itself has stopped. Separation is the whole measurement: a device that is
        // as far from you now as it was five minutes ago has not been tested at all.
        if (standing && left > state.tuning.shortlistMax) {
            return Guidance(
                stage = Stage.STALLED,
                headline = "Nothing is being ruled out while you stand still",
                expect = "This works by separation. Everything around you stays in range " +
                    "until you walk away from it, so a stationary phone eliminates nothing " +
                    "however long it listens.",
                move = Move.GET_MOVING,
                why = "Walk. Two minutes in any direction will take most of this list out " +
                    "of range.",
                urgent = true,
            )
        }

        if (elapsed < SETTLE_MS) {
            return Guidance(
                stage = Stage.SETTLING,
                headline = "Settling",
                expect = "The count will barely move for the first minute or so, and that " +
                    "is not the app being stuck. Everything you have walked past is still " +
                    "in range, and a device has to go ${state.tuning.dropAfterMs / 1000} " +
                    "seconds without a packet before it counts as gone.",
                move = Move.WAIT,
                why = "Keep walking at a normal pace and do not stop.",
            )
        }

        if (state.carried.size >= 2 && left > state.tuning.shortlistMax) {
            return Guidance(
                stage = Stage.THINNING,
                headline = "Some of these are in your own pocket",
                expect = "Whatever you are carrying survives every test here, because it " +
                    "goes where you go. Left in, it sits at the top of the list forever.",
                move = Move.OWN_KIT,
                why = "${state.carried.size} devices have not moved relative to you at all. " +
                    "Say whether they are yours.",
                urgent = true,
            )
        }

        if (left <= state.tuning.shortlistMax) {
            return Guidance(
                stage = Stage.IDENTIFY,
                headline = "Down to a pocket",
                expect = "This is about as far as elimination goes. What is left is small " +
                    "enough to be one person's devices, and more walking will not separate " +
                    "them from each other.",
                move = Move.HOLD,
                why = "Pick the one with the best case and hold it, or overtake them once " +
                    "more to be sure.",
            )
        }

        val stalled = stalled(counts, state.atMs)

        if (left <= PROBE_FROM || (stalled && left > state.tuning.shortlistMax)) {
            return Guidance(
                stage = if (stalled) Stage.STALLED else Stage.SHORTLIST,
                headline = if (stalled) "Not narrowing any further" else "Short list",
                expect = if (stalled) {
                    "What survives this long is other people going your way at your speed, " +
                        "and walking further does not separate them. Elimination has done " +
                        "what it can."
                } else {
                    "Down to a readable list. From here the fall gets slower, because what " +
                        "is left is moving with you rather than being left behind."
                },
                move = Move.WALK_BY,
                why = "Overtake them, or let them overtake you. One device will rise and " +
                    "fall as you draw level and the rest will not - it is the one test " +
                    "something in their pocket cannot fail.",
                urgent = stalled,
            )
        }

        return Guidance(
            stage = Stage.THINNING,
            headline = "Thinning",
            expect = "The list is collapsing as you leave things behind. Most of what was " +
                "around you at the start is fixed - shops, flats, parked cars - and every " +
                "one of them drops out a minute after it stops being audible.",
            move = Move.WALK,
            why = "Keep going. Corners help: something that was coincidentally alongside " +
                "you rarely turns the same way.",
        )
    }

    /**
     * Whether the count has stopped falling.
     *
     * Measured as a fraction rather than a number, because eight devices lost out of three
     * hundred is noise and eight lost out of twenty is the follow working.
     */
    fun stalled(counts: List<Moment.Count>, nowMs: Long): Boolean {
        val recent = counts.filter { it.atMs >= nowMs - STALL_WINDOW_MS }
        if (recent.size < 4) return false
        val then = recent.first().stillIn
        val now = recent.last().stillIn
        if (then <= 0) return false
        // A window that does not span most of the period has not had time to say anything.
        if (recent.last().atMs - recent.first().atMs < STALL_WINDOW_MS / 2) return false
        return (then - now).toDouble() / then < STALL_FRACTION
    }
}
