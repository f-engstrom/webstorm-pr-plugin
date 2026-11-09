package se.personal.adoprreview.jira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TicketBranchesTest {

    private fun issue(key: String, summary: String = "", type: String? = null, category: String? = null, status: String? = null) =
        JiraIssue(key, JiraIssueFields(summary, type?.let(::Named), JiraStatus(status, category?.let(::StatusCategory))))

    private fun transition(id: String, to: String, category: String) = Transition(id, to, JiraStatus(to, StatusCategory(category)))

    @Test
    fun `prefix comes from the issue type`() {
        assertEquals("bugfix/", TicketBranches.prefixFor("Bug"))
        assertEquals("bugfix/", TicketBranches.prefixFor(" bug "))
        assertEquals("feature/", TicketBranches.prefixFor("Story"))
        assertEquals("feature/", TicketBranches.prefixFor(null))
    }

    @Test
    fun `branch name is prefix plus key`() {
        assertEquals("feature/ABC-1", TicketBranches.branchName("feature/", "ABC-1"))
        assertEquals("hotfix/ABC-1", TicketBranches.branchName(" hotfix ", "ABC-1"))
        assertEquals("ABC-1", TicketBranches.branchName("", "ABC-1"))
    }

    @Test
    fun `typed keys are recognised`() {
        assertEquals("ABC-123", TicketBranches.asKey(" abc-123 "))
        assertNull(TicketBranches.asKey("abc 123"))
        assertNull(TicketBranches.asKey("login form"))
    }

    @Test
    fun `filter matches every word in key or summary`() {
        val issues = listOf(issue("ABC-1", "Add login form"), issue("ABC-2", "Fix logout"))
        assertEquals(listOf("ABC-1"), TicketBranches.filter(issues, "form LOG").map { it.key })
        assertEquals(listOf("ABC-2"), TicketBranches.filter(issues, "abc-2").map { it.key })
        assertEquals(2, TicketBranches.filter(issues, "  ").size)
    }

    @Test
    fun `existing branches are found by key`() {
        val branches = listOf("feature/ABC-1", "bugfix/abc-1-old", "feature/ABC-12", "main", "feature/ABC-1")
        assertEquals(listOf("bugfix/abc-1-old", "feature/ABC-1"), TicketBranches.branchesFor("ABC-1", branches))
    }

    @Test
    fun `in progress transition prefers one named In Progress`() {
        val todo = issue("ABC-1", category = "new", status = "To Do")
        val transitions = listOf(
            transition("11", "Done", "done"),
            transition("21", "In Review", "indeterminate"),
            transition("31", "In Progress", "indeterminate"),
        )
        assertEquals("31", TicketBranches.inProgressTransition(todo, transitions)?.id)
        assertEquals("21", TicketBranches.inProgressTransition(todo, transitions.filter { it.id != "31" })?.id)
        assertNull(TicketBranches.inProgressTransition(issue("ABC-1", category = "indeterminate"), transitions))
        assertNull(TicketBranches.inProgressTransition(todo, listOf(transition("11", "Done", "done"))))
    }

    @Test
    fun `pr title is key and summary`() {
        assertEquals("ABC-1 Add login form", TicketBranches.prTitle("ABC-1", " Add login form "))
        assertEquals("ABC-1", TicketBranches.prTitle("ABC-1", null))
    }
}
