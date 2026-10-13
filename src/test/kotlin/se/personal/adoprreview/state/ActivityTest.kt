package se.personal.adoprreview.state

import org.junit.Assert.assertEquals
import org.junit.Test
import se.personal.adoprreview.api.dto.Comment
import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.api.dto.IdentityRef
import se.personal.adoprreview.api.dto.PullRequest
import se.personal.adoprreview.api.dto.Reviewer
import se.personal.adoprreview.api.dto.Vote

class ActivityTest {

    private val me = "me"

    private fun pr(author: String, vararg reviewers: Reviewer) =
        PullRequest(pullRequestId = 7, title = "T", createdBy = IdentityRef(author, author), reviewers = reviewers.toList())

    private fun thread(id: Int, vararg comments: Pair<String, String>) = CommentThread(
        id = id,
        comments = comments.mapIndexed { i, (author, text) -> Comment(id = i + 1, author = IdentityRef(author, author), content = text, commentType = "text") },
    )

    @Test
    fun `new PR to review means added as reviewer, but my own new PR is silent`() {
        assertEquals(listOf(ActivityEvent.AddedAsReviewer), Activity.events(null, Activity.state(pr("ada"), emptyList(), me)))
        assertEquals(emptyList<ActivityEvent>(), Activity.events(null, Activity.state(pr(me), emptyList(), me)))
    }

    @Test
    fun `votes on my PR are reported, resets and my own vote are not`() {
        val before = Activity.state(pr(me, Reviewer("ada", "Ada", 0), Reviewer("bob", "Bob", 10)), emptyList(), me)
        val after = Activity.state(pr(me, Reviewer("ada", "Ada", 10), Reviewer("bob", "Bob", 0), Reviewer(me, "Me", 10)), emptyList(), me)
        assertEquals(listOf(ActivityEvent.Voted("Ada", Vote.APPROVED)), Activity.events(before, after))
    }

    @Test
    fun `all comments by others count on my PR`() {
        val before = Activity.state(pr(me), listOf(thread(1, "ada" to "first")), me)
        val after = Activity.state(pr(me), listOf(thread(1, "ada" to "first", me to "mine", "bob" to "reply")), me)
        assertEquals(listOf(ActivityEvent.Commented("bob", "reply", threadId = 1)), Activity.events(before, after))
    }

    @Test
    fun `on PRs I review only replies in my threads count`() {
        val before = Activity.state(pr("ada"), listOf(thread(1, me to "question"), thread(2, "bob" to "x")), me)
        val after = Activity.state(
            pr("ada"),
            listOf(thread(1, me to "question", "ada" to "answer"), thread(2, "bob" to "x", "carl" to "y")),
            me,
        )
        assertEquals(listOf(ActivityEvent.Commented("ada", "answer", threadId = 1)), Activity.events(before, after))
    }

    @Test
    fun `joining a thread doesn't report its earlier comments`() {
        val before = Activity.state(pr("ada"), listOf(thread(1, "bob" to "old")), me)
        val after = Activity.state(pr("ada"), listOf(thread(1, "bob" to "old", me to "joining")), me)
        assertEquals(emptyList<ActivityEvent>(), Activity.events(before, after))
    }

    @Test
    fun `go-to labels name the author and file`() {
        assertEquals("Go to Ada's comment (Button.tsx)", Activity.goToLabel(ActivityEvent.Commented("Ada", "x", 1, "/src/ui/Button.tsx")))
        assertEquals("Go to Bob's comment (general)", Activity.goToLabel(ActivityEvent.Commented("Bob", "x", 2)))
    }

    @Test
    fun `comment previews are one trimmed line`() {
        val long = "x".repeat(100)
        assertEquals("Ada: ${"x".repeat(80)}…", Activity.describe(ActivityEvent.Commented("Ada", "\n  $long\nmore", threadId = 1)))
    }
}
