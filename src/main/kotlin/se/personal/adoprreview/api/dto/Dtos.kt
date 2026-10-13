package se.personal.adoprreview.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ListResponse<T>(val value: List<T> = emptyList(), val count: Int = 0)

@Serializable
data class ConnectionData(val authenticatedUser: AuthenticatedUser)

@Serializable
data class AuthenticatedUser(val id: String, val providerDisplayName: String = "")

@Serializable
data class Identity(val id: String, val providerDisplayName: String? = null, val customDisplayName: String? = null) {
    val displayName: String? get() = customDisplayName?.takeIf { it.isNotBlank() } ?: providerDisplayName
}

@Serializable
data class IdentityRef(val id: String = "", val displayName: String = "", val uniqueName: String? = null)

@Serializable
data class Reviewer(
    val id: String = "",
    val displayName: String = "",
    val vote: Int = 0,
    val isRequired: Boolean = false,
    val hasDeclined: Boolean = false,
)

@Serializable
data class CommitRef(val commitId: String)

@Serializable
data class PullRequest(
    val pullRequestId: Int,
    val title: String = "",
    val description: String? = null,
    val status: String = "",
    val createdBy: IdentityRef = IdentityRef(),
    val creationDate: String = "",
    val sourceRefName: String = "",
    val targetRefName: String = "",
    val isDraft: Boolean = false,
    val reviewers: List<Reviewer> = emptyList(),
    val lastMergeSourceCommit: CommitRef? = null,
    val lastMergeTargetCommit: CommitRef? = null,
) {
    val sourceBranch: String get() = sourceRefName.removePrefix("refs/heads/")
    val targetBranch: String get() = targetRefName.removePrefix("refs/heads/")

    fun voteOf(userId: String?): Int = reviewers.firstOrNull { it.id.equals(userId, ignoreCase = true) }?.vote ?: 0
}

@Serializable
data class Iteration(
    val id: Int,
    val description: String? = null,
    val sourceRefCommit: CommitRef? = null,
    val targetRefCommit: CommitRef? = null,
    /** Merge base of source and target; the left side of the PR diff. */
    val commonRefCommit: CommitRef? = null,
)

@Serializable
data class ChangesResponse(val changeEntries: List<ChangeEntry> = emptyList(), val nextSkip: Int = 0, val nextTop: Int = 0)

@Serializable
data class ChangeEntry(
    val changeTrackingId: Int = 0,
    val changeId: Int = 0,
    val item: ChangeItem,
    /** E.g. `edit`, `add`, `delete`, `rename` or combinations such as `edit, rename`. */
    val changeType: String = "",
    val originalPath: String? = null,
) {
    /** Deleted files come with `item.path: null` and only `originalPath`. */
    val path: String get() = item.path ?: originalPath.orEmpty()

    val kind: ChangeKind
        get() = when {
            "delete" in changeType -> ChangeKind.DELETE
            "add" in changeType -> ChangeKind.ADD
            "rename" in changeType.lowercase() -> ChangeKind.RENAME
            else -> ChangeKind.EDIT
        }

    /** The path on the left (base) side. */
    val basePath: String get() = originalPath ?: path
}

@Serializable
data class ChangeItem(
    /** Null for deleted files; see [ChangeEntry.path]. */
    val path: String? = null,
    val objectId: String? = null,
    val originalObjectId: String? = null,
    val gitObjectType: String? = null,
    val isFolder: Boolean = false,
)

enum class ChangeKind { ADD, EDIT, DELETE, RENAME }

@Serializable
data class ReviewerVote(val vote: Int)

@Serializable
data class RepositoryInfo(val defaultBranch: String? = null)

@Serializable
data class NewPullRequest(
    val sourceRefName: String,
    val targetRefName: String,
    val title: String,
    val description: String,
    val isDraft: Boolean,
)

/** Body of `PATCH .../pullrequests/{id}`; null fields are left unchanged. */
@Serializable
data class PullRequestUpdate(val title: String? = null, val description: String? = null, val isDraft: Boolean? = null)

/** Reviewer vote values used by the REST API. [label] describes the state, [action] the menu item that sets it. */
enum class Vote(val value: Int, val label: String, val action: String) {
    APPROVED(10, "Approved", "Approve"),
    APPROVED_WITH_SUGGESTIONS(5, "Approved with suggestions", "Approve with suggestions"),
    WAITING_FOR_AUTHOR(-5, "Waiting for author", "Wait for author"),
    REJECTED(-10, "Rejected", "Reject"),
    NONE(0, "No vote", "Reset vote");

    companion object {
        fun of(value: Int): Vote = entries.firstOrNull { it.value == value } ?: NONE
    }
}
