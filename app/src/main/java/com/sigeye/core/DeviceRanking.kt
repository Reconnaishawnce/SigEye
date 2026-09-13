package com.sigeye.core

/**
 * Ordering for any screen that asks you to choose a device.
 *
 * A chooser sorted by signal strength is complete and useless: the loudest thing in range
 * is almost always something anonymous, so the devices you have actually named or watched
 * end up buried under a wall of hex. What you can reason about should come first, and only
 * then whatever happens to be loud.
 *
 * Shared rather than per screen because every picker in the app has the same problem, and
 * solving it once in the Motion picker would have meant solving it again four more times.
 */
object DeviceRanking {

    /** Lower sorts first. */
    const val WATCHED = 0
    const val NICKNAMED = 1
    const val LISTED = 2
    const val IDENTIFIED = 3
    const val ANONYMOUS = 4

    /**
     * @param hasIdentity the advertisement carried a name or a recognizable vendor - that
     *   is, the row will read as something other than raw hex.
     */
    fun rank(
        watched: Boolean,
        nickname: String?,
        lists: Set<String>,
        hasIdentity: Boolean,
    ): Int = when {
        watched -> WATCHED
        !nickname.isNullOrBlank() -> NICKNAMED
        lists.isNotEmpty() -> LISTED
        hasIdentity -> IDENTIFIED
        else -> ANONYMOUS
    }
}
