package se.personal.adoprreview.jira

import kotlinx.serialization.Serializable

/** `GET /rest/api/3/myself`. */
@Serializable
data class JiraUser(val accountId: String, val displayName: String? = null, val emailAddress: String? = null)

@Serializable
data class JiraIssue(val key: String, val fields: JiraIssueFields = JiraIssueFields()) {
    val summary: String get() = fields.summary.orEmpty()
    val issueType: String? get() = fields.issuetype?.name
    val statusName: String? get() = fields.status?.name
    val statusCategory: String? get() = fields.status?.statusCategory?.key
    val assigneeId: String? get() = fields.assignee?.accountId
}

@Serializable
data class JiraIssueFields(
    val summary: String? = null,
    val issuetype: Named? = null,
    val status: JiraStatus? = null,
    val assignee: JiraUser? = null,
)

@Serializable
data class Named(val name: String? = null)

/** [statusCategory] `key` is `new`, `indeterminate` (in progress) or `done`. */
@Serializable
data class JiraStatus(val name: String? = null, val statusCategory: StatusCategory? = null)

@Serializable
data class StatusCategory(val key: String? = null)

/** Body of `POST /rest/api/3/search/jql`. */
@Serializable
data class JqlSearch(val jql: String, val fields: List<String>, val maxResults: Int)

@Serializable
data class JqlSearchResult(val issues: List<JiraIssue> = emptyList())

@Serializable
data class Transitions(val transitions: List<Transition> = emptyList())

@Serializable
data class Transition(val id: String, val name: String? = null, val to: JiraStatus? = null)

@Serializable
data class TransitionRequest(val transition: TransitionId)

@Serializable
data class TransitionId(val id: String)

@Serializable
data class AssigneeRequest(val accountId: String)
