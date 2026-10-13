package se.personal.adoprreview.api

import org.junit.Assert.assertEquals
import org.junit.Test

class MentionsTest {

    private val id = "6B1D7C3A-1F2E-4D5C-9A8B-0C1D2E3F4A5B"

    @Test
    fun `ids are found and lower-cased`() {
        assertEquals(setOf(id.lowercase()), Mentions.ids("Hi @<$id>, see @<$id> and @<not-a-guid>"))
        assertEquals(emptySet<String>(), Mentions.ids(null))
    }

    @Test
    fun `mentions become bold names`() {
        assertEquals("Hi **@Ada Lovelace**!", Mentions.replace("Hi @<$id>!", mapOf(id.lowercase() to "Ada Lovelace")))
    }

    @Test
    fun `unknown ids and markdown in names are handled`() {
        assertEquals("**@unknown user**", Mentions.replace("@<$id>", emptyMap()))
        assertEquals("**@a\\*b**", Mentions.replace("@<$id>", mapOf(id.lowercase() to "a*b")))
    }
}
