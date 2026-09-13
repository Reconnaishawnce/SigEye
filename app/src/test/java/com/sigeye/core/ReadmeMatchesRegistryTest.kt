package com.sigeye.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The README says it is a snapshot of the registry, so this makes that true.
 *
 * It had drifted by six experiments before anybody noticed, which is the normal fate of a
 * list kept in two places by hand. The README is the first thing anybody reads about this
 * app and the registry is what the app actually does; a README that quietly under-reports
 * by six is worse than no list at all, because it reads as authoritative.
 *
 * Only the titles are checked, not the one-line descriptions. The two are written for
 * different readers - the registry's blurb appears on a phone under a thumb, the README's
 * in a table on a laptop - and forcing them to be identical would make both worse.
 */
class ReadmeMatchesRegistryTest {

    private fun readme(): String =
        listOf("../README.md", "README.md")
            .map { File(it) }
            .firstOrNull { it.exists() }
            ?.readText()
            ?: error("README.md not found from ${File(".").absolutePath}")

    @Test
    fun `every experiment in the registry is listed in the README`() {
        val text = readme()
        val missing = Experiments.all.map { it.title }.filterNot { text.contains("**$it**") }

        assertTrue(
            "The README is a snapshot of core/Experiment.kt and is now missing: " +
                missing.joinToString(", ") + ". Add a row, or say plainly that the README " +
                "is not a full list.",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the README says how many there are, and is right`() {
        // The count in the opening sentence is the one thing somebody takes away without
        // reading the table, so it is the one most worth holding to the truth.
        val text = readme()
        val words = mapOf(
            28 to "Twenty-eight", 29 to "Twenty-nine", 30 to "Thirty", 31 to "Thirty-one",
            32 to "Thirty-two", 33 to "Thirty-three", 34 to "Thirty-four",
            35 to "Thirty-five", 36 to "Thirty-six", 37 to "Thirty-seven",
            38 to "Thirty-eight", 39 to "Thirty-nine", 40 to "Forty",
        )
        val expected = words[Experiments.all.size]
            ?: error("Add ${Experiments.all.size} to the number words in this test.")

        assertTrue(
            "The README opens by saying how many experiments there are. There are now " +
                "${Experiments.all.size}, so it should say \"$expected of them\".",
            text.contains("$expected of them"),
        )
    }

    @Test
    fun `the README's tier counts match the registry`() {
        // Digits rather than words in that paragraph, deliberately, so this can check them
        // without carrying a table of English number names around.
        val text = readme()

        assertEquals(
            "Every experiment is Active, Beta or In development and nothing else.",
            Experiments.all.size,
            Experiment.Status.entries.sumOf { Experiments.count(it) },
        )

        Experiment.Status.entries.forEach { status ->
            val count = Experiments.count(status)
            assertTrue(
                "The README lists how many are ${status.label}; there are $count.",
                text.contains("$count of them are"),
            )
        }
    }

    @Test
    fun `every experiment that is not Active is marked as such in the README`() {
        // The badge is the whole point of the tiers. A Beta experiment presented in the
        // README as though it were finished is the one place that would not show.
        val text = readme()

        Experiments.all.forEach { experiment ->
            val marker = when (experiment.status) {
                Experiment.Status.ACTIVE -> return@forEach
                Experiment.Status.BETA -> "*(beta)*"
                Experiment.Status.DEVELOPMENT -> "*(soon)*"
            }
            assertTrue(
                "${experiment.title} is ${experiment.status.label}, so the README should " +
                    "mark it $marker.",
                text.contains("**${experiment.title}** $marker"),
            )
        }
    }
}
