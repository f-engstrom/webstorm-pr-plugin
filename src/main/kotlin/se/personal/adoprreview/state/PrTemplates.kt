package se.personal.adoprreview.state

/** Defaults for new PRs, following the team's convention: `ABC-123 <Jira summary>` and a link to the ticket. */
object PrTemplates {
    private val TICKET = Regex("""(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9]+-\d+)(?![0-9])""")

    /** The Jira key in a branch name, e.g. `feature/ABC-123-forms` → `ABC-123`. */
    fun ticketKey(branch: String): String? = TICKET.find(branch.substringAfterLast('/'))?.groupValues?.get(1)?.uppercase()
        ?: TICKET.find(branch)?.groupValues?.get(1)?.uppercase()

    /** `ABC-123 ` (the summary is typed by the user), or the last commit subject when the branch has no key. */
    fun title(branch: String, lastCommitSubject: String?): String =
        ticketKey(branch)?.let { "$it " } ?: lastCommitSubject.orEmpty()

    /** A Markdown link to the ticket, or empty when there is no key or no Jira site. */
    fun description(branch: String, jiraSite: String?): String {
        val key = ticketKey(branch) ?: return ""
        val site = jiraSite?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return ""
        return "[$key]($site/browse/$key)"
    }
}
