package com.sigeye.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one true sentence every screen owes the person reading it.
 *
 * Somebody standing in a corridor reads the heading, the big number, and nothing else. The
 * blurb is the only line between those two, it is rendered by the shared header on all
 * twenty-eight screens, and it is the only chance the app gets to say what the number
 * means before somebody decides what it means for themselves.
 *
 * So it is held to a shape. These rules are mechanical and a sentence can satisfy every one
 * of them and still be useless - what they catch is the specific ways this copy went wrong
 * before: a question where a statement belonged, a second sentence nobody reads, and the
 * slow drift toward a paragraph.
 */
class ExperimentCopyTest {

    /** Long enough to say something, short enough to be read above a headline number. */
    private val maxBlurb = 80

    @Test
    fun `every experiment says what it is, what it measures, and where it stands`() {
        Experiments.all.forEach { experiment ->
            assertTrue("${experiment.id} has no title", experiment.title.isNotBlank())
            assertTrue("${experiment.id} has no blurb", experiment.blurb.isNotBlank())
            assertTrue("${experiment.id} has no teaches", experiment.teaches.isNotBlank())
        }
    }

    @Test
    fun `the blurb is one statement, not a question and not a paragraph`() {
        Experiments.all.forEach { experiment ->
            val blurb = experiment.blurb
            val where = "${experiment.id}: \"$blurb\""

            assertTrue(
                "$where asks a question. Somebody reading one line wants to be told what " +
                    "the number is, not asked.",
                !blurb.contains('?'),
            )
            assertTrue("$where does not end in a full stop", blurb.endsWith("."))
            assertTrue(
                "$where is two sentences. Only the first gets read.",
                !blurb.dropLast(1).contains(". "),
            )
            assertTrue(
                "$where is ${blurb.length} characters, over $maxBlurb",
                blurb.length <= maxBlurb,
            )
        }
    }

    @Test
    fun `anything openable explains itself, including what it cannot tell you`() {
        // DEVELOPMENT entries are descriptions of things that do not exist yet and open
        // nothing, so they owe nobody a walkthrough.
        Experiments.all.filter { it.status.openable }.forEach { experiment ->
            assertTrue("${experiment.id} has no howTo", experiment.howTo.isNotEmpty())
            assertTrue("${experiment.id} has no reading", experiment.reading != null)
            assertTrue(
                "${experiment.id} has no limits. Every measurement has some, and this is " +
                    "the section that gets skipped when it is optional.",
                experiment.limits != null,
            )
        }
    }

    @Test
    fun `ids are unique, because favorites and CSV headers are written with them`() {
        val duplicated = Experiments.all.groupBy { it.id }.filterValues { it.size > 1 }

        assertTrue("duplicate ids: ${duplicated.keys}", duplicated.isEmpty())
    }

    /**
     * The structural half of the same rule.
     *
     * [ExperimentHeader][com.sigeye.ui.ExperimentHeader] falls back to printing the raw id
     * with no blurb at all when the id is not registered, which is exactly the failure this
     * is meant to prevent and is invisible until somebody opens that screen. Reading the
     * source is the only way to catch it without an instrumented test per screen, and it is
     * the same trick GattWriteRuleTest uses.
     */
    @Test
    fun `every screen passes the header an experiment that actually exists`() {
        val constants = Regex("""const val (\w+) = "([^"]+)"""")
            .findAll(File(SOURCE, "core/Experiment.kt").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertTrue("no Experiments constants found - did the file move?", constants.isNotEmpty())

        val registered = Experiments.all.map { it.id }.toSet()
        val screens = File(SOURCE, "experiments").walkTopDown().filter { it.extension == "kt" }
        var checked = 0

        screens.forEach { file ->
            Regex("""ExperimentHeader\(\s*(?:\w+\s*=\s*)?(?:[\w.]*\.)?Experiments\.(\w+)""")
                .findAll(file.readText())
                .forEach { match ->
                    val name = match.groupValues[1]
                    val id = constants[name]
                    assertTrue("${file.name} uses Experiments.$name, which is not a constant", id != null)
                    assertTrue(
                        "${file.name} opens with Experiments.$name (\"$id\"), which is not " +
                            "in Experiments.all - that screen would show a bare id and no " +
                            "sentence at all",
                        id in registered,
                    )
                    checked++
                }
        }

        assertTrue("found no ExperimentHeader call sites, so this test proved nothing", checked >= 25)
    }

    private companion object {
        /** Tests run from the module directory, so this reaches the sources beside them. */
        val SOURCE = File("src/main/java/com/sigeye")
    }
}
