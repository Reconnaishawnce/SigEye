package com.sigeye.core.analysis.identity

import java.util.Locale

/**
 * Typing a few letters and keeping only what matches.
 *
 * A busy room fills any list in this app with sixty rows, and the one you want is a JBL
 * speaker or a Bose headset or a device whose address you half remember. Scrolling is the
 * wrong tool for that. Four letters is the right one.
 *
 * Matching covers everything a row can show: the name you gave it, the name it advertises,
 * the vendor, and the address. Addresses are compared with the colons taken out, so
 * "AABBCC" and "AA:BB:CC" both find the same thing and neither needs to be complete.
 *
 * Several words all have to match, which is what makes narrowing feel quick: "jbl 40"
 * keeps the JBL sitting at -40 and drops the other three. They may match different fields.
 *
 * Pure and Android-free.
 */
object DeviceQuery {

    /** One row, reduced to the text a person might search it by. */
    data class Subject(
        val address: String,
        val nickname: String? = null,
        val name: String? = null,
        val vendor: String? = null,
        /** Anything else worth matching on: a beacon protocol, a note, a signal reading. */
        val extra: String? = null,
    )

    /**
     * Whether [subject] matches everything in [query].
     *
     * An empty query matches everything, so a filter nobody has typed into hides nothing.
     */
    fun matches(subject: Subject, query: String): Boolean {
        val terms = query.trim().lowercase(Locale.US).split(" ").filter { it.isNotBlank() }
        if (terms.isEmpty()) return true

        val haystack = buildString {
            append(subject.address.lowercase(Locale.US))
            append(' ')
            append(subject.address.replace(":", "").lowercase(Locale.US))
            subject.nickname?.let { append(' ').append(it.lowercase(Locale.US)) }
            subject.name?.let { append(' ').append(it.lowercase(Locale.US)) }
            subject.vendor?.let { append(' ').append(it.lowercase(Locale.US)) }
            subject.extra?.let { append(' ').append(it.lowercase(Locale.US)) }
        }

        return terms.all { term ->
            haystack.contains(term) || haystack.contains(term.replace(":", ""))
        }
    }

    /** Convenience for the common case of filtering a list. */
    fun <T> filter(items: List<T>, query: String, subject: (T) -> Subject): List<T> {
        if (query.isBlank()) return items
        return items.filter { matches(subject(it), query) }
    }

    /**
     * What to say when a filter has hidden everything.
     *
     * A list that empties itself with no explanation reads as a broken app, and the fix is
     * one sentence naming the thing that did it.
     */
    fun emptyNote(query: String, total: Int): String = when {
        total == 0 -> "Nothing in range yet."
        else -> "None of the $total devices in range match \"${query.trim()}\"."
    }
}
