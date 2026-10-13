package se.personal.adoprreview.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrTemplatesTest {

    @Test
    fun `ticket key comes from the branch`() {
        assertEquals("ABC-123", PrTemplates.ticketKey("feature/ABC-123"))
        assertEquals("ABC-456", PrTemplates.ticketKey("bugfix/ABC-456-d02"))
        assertEquals("ABC-789", PrTemplates.ticketKey("feature/abc-789-lower-case"))
        assertNull(PrTemplates.ticketKey("azure-pipelines"))
        assertNull(PrTemplates.ticketKey("bugfix/8529"))
    }

    @Test
    fun `title starts with the key, or falls back to the last commit`() {
        assertEquals("ABC-123 ", PrTemplates.title("feature/ABC-123", "wip"))
        assertEquals("Fix pipeline", PrTemplates.title("azure-pipelines", "Fix pipeline"))
        assertEquals("", PrTemplates.title("azure-pipelines", null))
    }

    @Test
    fun `description links the ticket when the Jira site is known`() {
        assertEquals("[ABC-123](https://your-company.atlassian.net/browse/ABC-123)", PrTemplates.description("feature/ABC-123", "https://your-company.atlassian.net/"))
        assertEquals("", PrTemplates.description("feature/ABC-123", null))
        assertEquals("", PrTemplates.description("azure-pipelines", "https://your-company.atlassian.net"))
    }
}
