package com.sigeye.core

import android.content.Context

/**
 * Which tab a suite was left on.
 *
 * Identity and the bench both hold several screens behind one card, and without this every
 * visit starts at the leftmost tab. That is wrong for the way they are actually used:
 * somebody measuring wall penetration in six rooms goes in and out of that one screen a
 * dozen times, and being dropped back on path loss each time is a small insult repeated all
 * afternoon.
 *
 * `rememberSaveable` does not cover it. It survives a rotation and a process death, and it
 * is discarded the moment the screen leaves the back stack, which is exactly when somebody
 * steps out to check something and comes back.
 *
 * A link that names a tab still wins. Opening Defeating Randomization for a specific device
 * means that tab, whatever was last used.
 */
class SuiteMemory private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("suites", Context.MODE_PRIVATE)

    fun lastTab(suite: String): String? = prefs.getString(suite, null)

    fun remember(suite: String, tab: String) {
        prefs.edit().putString(suite, tab).apply()
    }

    companion object {
        @Volatile
        private var instance: SuiteMemory? = null

        fun get(context: Context): SuiteMemory =
            instance ?: synchronized(this) {
                instance ?: SuiteMemory(context).also { instance = it }
            }
    }
}
