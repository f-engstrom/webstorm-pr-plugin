package se.personal.adoprreview.jira

import se.personal.adoprreview.state.PrTemplates

/** Pure rules for "Start Jira ticket": branch names, prefixes, search and which transition means "In Progress". */
object TicketBranches {

    const val MY_OPEN_JQL = "assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC"

    val PREFIXES = listOf("feature/", "bugfix/", "hotfix/", "chore/")

    private val KEY = Regex("""^[A-Za-z][A-Za-z0-9]+-\d+$""")

    /** `bugfix/` for bugs, else `feature/`. */
    fun prefixFor(issueType: String?): String =
        if (issueType?.trim()?.equals("bug", ignoreCase = true) == true) "bugfix/" else "feature/"

    /** `feature/` + `ABC-123`; the prefix gets a trailing slash if it has none, an empty prefix gives just the key. */
    fun branchName(prefix: String, key: String): String {
        val p = prefix.trim()
        return when {
            p.isEmpty() -> key
            p.endsWith('/') -> p + key
            else -> "$p/$key"
        }
    }

    /** The upper-cased key when [text] is exactly a ticket key like `abc-123`. */
    fun asKey(text: String): String? = text.trim().takeIf { KEY.matches(it) }?.uppercase()

    /** Tickets whose key or summary contains every word of [query]. */
    fun filter(issues: List<JiraIssue>, query: String): List<JiraIssue> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return issues
        return issues.filter { issue ->
            val haystack = "${issue.key} ${issue.summary}".lowercase()
            words.all { it in haystack }
        }
    }

    /** Branch names (local or remote, without the remote prefix) that already carry [key]. */
    fun branchesFor(key: String, branches: Collection<String>): List<String> =
        branches.filter { PrTemplates.ticketKey(it) == key }.distinct().sorted()

    /** The first transition into an "In Progress"-category status, unless the ticket is already in one. */
    fun inProgressTransition(issue: JiraIssue, transitions: List<Transition>): Transition? {
        if (issue.statusCategory == "indeterminate") return null
        val candidates = transitions.filter { it.to?.statusCategory?.key == "indeterminate" }
        return candidates.firstOrNull { it.to?.name.equals("In Progress", ignoreCase = true) } ?: candidates.firstOrNull()
    }

    /** `ABC-123 Summary`, the team's PR title convention. */
    fun prTitle(key: String, summary: String?): String = listOfNotNull(key, summary?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" ")
}
