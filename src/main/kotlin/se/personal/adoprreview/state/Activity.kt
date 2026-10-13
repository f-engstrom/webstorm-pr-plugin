package se.personal.adoprreview.state

import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.api.dto.PullRequest
import se.personal.adoprreview.api.dto.Vote

/** What the background watcher remembers about one PR between polls. */
data class PrActivityState(
    val prId: Int,
    val title: String,
    val iAmAuthor: Boolean,
    /** Reviewer ID to (name, vote). */
    val votes: Map<String, Pair<String, Int>>,
    /** Comments by others that concern me, keyed `threadId/commentId`. */
    val comments: Map<String, SeenComment>,
)

data class SeenComment(val threadId: Int, val author: String, val text: String, val filePath: String? = null)

sealed interface ActivityEvent {
    data object AddedAsReviewer : ActivityEvent
    data class Voted(val reviewer: String, val vote: Vote) : ActivityEvent
    data class Commented(val author: String, val text: String, val threadId: Int, val filePath: String? = null) : ActivityEvent
}

object Activity {

    /**
     * The state of [pr] as seen by [myId]. On my own PRs every comment by others counts; on PRs I review,
     * only comments by others that came after my first comment in the thread (replies to me). Earlier comments
     * would otherwise look new as soon as I join a thread.
     */
    fun state(pr: PullRequest, threads: List<CommentThread>, myId: String): PrActivityState {
        val iAmAuthor = pr.createdBy.id.equals(myId, ignoreCase = true)
        val comments = threads.flatMap { thread ->
            val mine = thread.visibleComments.filter { it.author.id.equals(myId, ignoreCase = true) }
            val after = if (iAmAuthor) Int.MIN_VALUE else mine.minOfOrNull { it.id } ?: return@flatMap emptyList()
            thread.visibleComments
                .filter { it.id > after && !it.author.id.equals(myId, ignoreCase = true) }
                .map { "${thread.id}/${it.id}" to SeenComment(thread.id, it.author.displayName, it.content.orEmpty(), thread.filePath) }
        }.toMap()
        val votes = pr.reviewers
            .filter { !it.id.equals(myId, ignoreCase = true) }
            .associate { it.id.lowercase() to (it.displayName to it.vote) }
        return PrActivityState(pr.pullRequestId, pr.title, iAmAuthor, votes, comments)
    }

    /** Events since [before] (null: the PR is new to the watcher). Only my PRs report votes. */
    fun events(before: PrActivityState?, after: PrActivityState): List<ActivityEvent> {
        if (before == null) return if (after.iAmAuthor) emptyList() else listOf(ActivityEvent.AddedAsReviewer)
        val events = mutableListOf<ActivityEvent>()
        if (after.iAmAuthor) {
            for ((id, nameAndVote) in after.votes) {
                val (name, vote) = nameAndVote
                if (vote != Vote.NONE.value && before.votes[id]?.second != vote) events += ActivityEvent.Voted(name, Vote.of(vote))
            }
        }
        for ((key, comment) in after.comments) {
            if (key !in before.comments) events += ActivityEvent.Commented(comment.author, comment.text, comment.threadId, comment.filePath)
        }
        return events
    }

    /** Label for a "go to this thread" action: whose comment, and where. */
    fun goToLabel(comment: ActivityEvent.Commented): String =
        "Go to ${comment.author}'s comment (${comment.filePath?.substringAfterLast('/') ?: "general"})"

    /** One line per event for the notification body. */
    fun describe(event: ActivityEvent): String = when (event) {
        ActivityEvent.AddedAsReviewer -> "You were added as a reviewer"
        is ActivityEvent.Voted -> "${event.reviewer}: ${event.vote.label}"
        is ActivityEvent.Commented -> {
            val preview = event.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            "${event.author}: ${if (preview.length > 80) preview.take(80) + "…" else preview}"
        }
    }
}
