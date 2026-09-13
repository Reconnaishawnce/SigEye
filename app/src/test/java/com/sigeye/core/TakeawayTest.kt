package com.sigeye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeawayTest {

    private fun takeaway(
        headline: String = "3",
        denominator: String = "of 214 in range",
        limit: String = "Co-presence is not identity.",
        context: List<String> = listOf("2 legs", "1.4 km"),
    ) = Takeaway(
        experiment = "Follow Me",
        headline = headline,
        unit = "devices still with you",
        denominator = denominator,
        context = context,
        limit = limit,
        takenAtMs = 1_700_000_000_000L,
    )

    @Test
    fun `a number with nothing to divide it by cannot be built`() {
        // The type is the enforcement. Three devices is a headline; three out of two
        // hundred and fourteen is a finding, and the difference is the whole argument.
        val thrown = runCatching { takeaway(denominator = "") }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
        assertTrue(thrown!!.message!!.contains("what its number is out of"))
    }

    @Test
    fun `a takeaway that does not say what it fails to prove cannot be built`() {
        val thrown = runCatching { takeaway(limit = "   ") }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
        assertTrue(thrown!!.message!!.contains("does not prove"))
    }

    @Test
    fun `an empty headline is refused too`() {
        assertTrue(runCatching { takeaway(headline = "") }.isFailure)
    }

    @Test
    fun `conditions read as one line and drop the blanks`() {
        // Screens build this list from optional pieces, so a missing leg count arrives as
        // an empty string rather than as a shorter list.
        assertEquals(
            "2 legs · 1.4 km",
            takeaway(context = listOf("2 legs", "", "1.4 km")).conditions(),
        )
        assertEquals("", takeaway(context = emptyList()).conditions())
    }

    @Test
    fun `the text form carries the denominator and the limit, in that order`() {
        // Somebody who pastes this into a message has to be making the same claim the card
        // makes, or the two versions disagree about how strong the result was.
        val text = takeaway().asText()

        assertTrue(text.contains("3 devices still with you"))
        assertTrue(text.contains("of 214 in range"))
        assertTrue(text.contains("2 legs · 1.4 km"))
        assertTrue(text.contains("Co-presence is not identity."))
        assertTrue(text.indexOf("of 214") < text.indexOf("Co-presence"))
        assertTrue(text.contains("Follow Me · SigEye"))
    }

    @Test
    fun `a takeaway with no conditions still reads properly`() {
        val text = takeaway(context = emptyList()).asText()

        assertTrue(text.contains("of 214 in range"))
        assertTrue(text.contains("Co-presence is not identity."))
        assertTrue("no stray blank run where the conditions were", !text.contains("\n\n\n"))
    }
}
