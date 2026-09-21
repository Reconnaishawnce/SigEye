package com.sigeye.core.analysis.identity

/**
 * What to do with a rotation question: interrupt, queue it, or keep quiet about it.
 *
 * Nothing is ever thrown away. The weakest outcome is still answerable, just not in front
 * of somebody who is walking.
 */
enum class AskUrgency {
    /** Worth a dialog over whatever is on screen. */
    INTERRUPT,

    /** Goes in the tray with a count on it, to be opened when somebody chooses to. */
    TRAY,
}

/**
 * Deciding which rotation questions are worth interrupting a walk for.
 *
 * The flood had one cause and it was not a threshold. [Follow] bridges every member of the
 * pool, and early in a follow the pool is dozens of devices nobody has looked at, so a
 * question would arrive reading "did A4:C1:38 become 7B:09:EE?" about a device the person
 * had never seen, could not point at, and had no way to judge. Answering it correctly was
 * not possible; the only available responses were a guess and Later. Then the screen
 * offered the next one, immediately, because one-question-at-a-time means the queue drains
 * straight into your face.
 *
 * A question somebody cannot answer should never have been a dialog. That is the whole
 * change. Everything still gets asked, and nothing is auto-guessed - inventing a link to
 * avoid a prompt would be far worse than the prompt - but only questions with a chance of
 * a real answer are allowed to stop what somebody is doing.
 *
 * Two things make a question answerable. Either it is about the device the person chose,
 * in which case they are watching it and can say. Or the pool has narrowed far enough that
 * the devices in it are a handful somebody has been looking at for a while, in which case
 * they have a chance. Everything else waits in the tray, where it is exactly as answerable
 * later as it was going to be at the time.
 *
 * Pure and Android-free.
 */
object AskPolicy {

    /**
     * Quiet time between interruptions.
     *
     * Not a rate limit on questions, which are unlimited and all kept. A rate limit on
     * being stopped. Somebody navigating a street corner cannot read two dialogs in a
     * minute and the second one gets tapped through unread, which is worse than not
     * showing it: it turns a deliberate answer into a reflex.
     */
    const val MIN_GAP_MS = 45_000L

    /**
     * Pool size at or below which any member is worth asking about.
     *
     * By the time a follow is down to a few devices, the person has been watching those
     * few for several minutes and has a feel for them. Above it they are strangers in a
     * list and the question is unanswerable however it is phrased.
     */
    const val POOL_SMALL = 5

    /**
     * Whether this question gets to interrupt.
     *
     * @param departureAddress the address that went quiet.
     * @param pinnedAddress the device the person chose, if they have chosen one.
     * @param poolSize how many devices are still being followed.
     * @param lastInterruptMs when a dialog was last put in front of them, or 0.
     * @param muted set when they have asked not to be interrupted for the rest of the run.
     */
    fun urgencyOf(
        departureAddress: String,
        pinnedAddress: String?,
        poolSize: Int,
        lastInterruptMs: Long,
        nowMs: Long,
        muted: Boolean = false,
    ): AskUrgency {
        if (muted) return AskUrgency.TRAY

        val aboutTheTarget = pinnedAddress != null &&
            departureAddress.equals(pinnedAddress, ignoreCase = true)

        // A pinned device rotating is the event the whole experiment exists for, and the
        // answer is time-limited because the successor has to still be audible. It is
        // worth the interruption even so, but not worth two in a row.
        val worthAsking = aboutTheTarget || poolSize <= POOL_SMALL
        if (!worthAsking) return AskUrgency.TRAY

        val quietLongEnough = lastInterruptMs <= 0L || nowMs - lastInterruptMs >= MIN_GAP_MS
        return if (quietLongEnough) AskUrgency.INTERRUPT else AskUrgency.TRAY
    }

    /**
     * What the tray badge should say.
     *
     * Plain counting rather than a severity, because the tray is not an alarm. It is a
     * pile of work somebody can choose to do, and the only thing they need in order to
     * decide is how big the pile is.
     */
    fun trayLabel(waiting: Int): String? = when {
        waiting <= 0 -> null
        waiting == 1 -> "1 rotation to check"
        else -> "$waiting rotations to check"
    }

    /**
     * Why a question is waiting instead of being asked, in words somebody can act on.
     *
     * Shown on the tray rather than left implicit, because a queue that fills up with no
     * explanation reads like the app falling behind.
     */
    fun whyWaiting(poolSize: Int, muted: Boolean): String = when {
        muted -> "You turned off interruptions for this run. These are still here to answer."
        poolSize > POOL_SMALL ->
            "Still too many devices in the pool for these to be worth stopping you for. " +
                "A question about a device you have not picked out yet is one nobody can " +
                "answer. They keep until the pool narrows, or until you open them."

        else -> "Asked recently, so these are waiting rather than stacking up on screen."
    }
}
