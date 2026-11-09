package se.personal.adoprreview.state

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.personal.adoprreview.api.dto.Comment
import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.api.dto.CommitRef
import se.personal.adoprreview.api.dto.IdentityRef
import se.personal.adoprreview.api.dto.PullRequest

class PrActivityTest {

    private val me = "me"
    private val org = "org"
    private val repoKey = "org/project/repo"

    private val snapshots = mutableMapOf<Int, Snapshot>()
    private val activity = PrActivity { _, prId -> snapshots[prId] }

    private fun pr(id: Int, commit: String = "c1", author: String = "ada") =
        PullRequest(pullRequestId = id, title = "PR $id", createdBy = IdentityRef(author, author), lastMergeSourceCommit = CommitRef(commit))

    private fun seen(commit: String = "c1", activity: String? = "2026-11-01T10:00:00Z") = Snapshot(1, commit, activity)

    private fun commentAt(time: String, author: String = "bob") =
        listOf(CommentThread(1, comments = listOf(Comment(1, author = IdentityRef(author, author), content = "x", publishedDate = time))))

    private fun state(prId: Int, comments: Map<String, SeenComment> = emptyMap(), iAmAuthor: Boolean = false) =
        PrActivityState(prId, "PR $prId", iAmAuthor, emptyMap(), comments)

    private fun comment(thread: Int) = "$thread/1" to SeenComment(thread, "bob", "hello")

    // ---- List badges ----

    @Test
    fun `listed PRs get New, New commits or nothing from what was last seen`() {
        snapshots[2] = seen("old")
        snapshots[3] = seen("c1")
        activity.listed(org, listOf(pr(1), pr(2), pr(3)))
        assertEquals(mapOf(1 to setOf(Badge.NEW), 2 to setOf(Badge.NEW_COMMITS), 3 to emptySet()), activity.badges.value)
    }

    @Test
    fun `the thread check adds New activity for comments by others since last seen`() = runBlocking {
        snapshots[1] = seen()
        snapshots[2] = seen()
        snapshots[3] = seen()
        activity.listed(org, listOf(pr(1), pr(2), pr(3), pr(4)))
        val fetched = mutableListOf<Int>()
        activity.checkThreads(me) { prId ->
            fetched += prId
            when (prId) {
                1 -> commentAt("2026-11-02T10:00:00Z")             // newer, by someone else
                2 -> commentAt("2026-11-02T10:00:00Z", author = me) // newer, but mine
                else -> error("offline")                             // skipped
            }
        }
        assertEquals(setOf(Badge.NEW_ACTIVITY), activity.badges.value[1])
        assertEquals(emptySet<Badge>(), activity.badges.value[2])
        assertEquals(emptySet<Badge>(), activity.badges.value[3])
        assertFalse("never-opened PRs need no threads", 4 in fetched)
    }

    @Test
    fun `opening a PR mid-check wins over the stale snapshot`() = runBlocking {
        snapshots[1] = seen()
        activity.listed(org, listOf(pr(1)))
        activity.checkThreads(me) {
            snapshots[1] = seen(activity = "2026-11-03T00:00:00Z") // the user opened it while the fetch ran
            commentAt("2026-11-02T10:00:00Z")
        }
        assertEquals(emptySet<Badge>(), activity.badges.value[1])
    }

    @Test
    fun `seeing a PR clears its activity badge and dot`() {
        snapshots[1] = seen()
        activity.listed(org, listOf(pr(1)))
        activity.polled(repoKey, mapOf(1 to state(1)))
        activity.polled(repoKey, mapOf(1 to state(1, mapOf(comment(5)))))
        assertEquals(setOf(Badge.NEW_ACTIVITY), activity.badges.value[1])
        assertEquals(setOf(1), activity.unseen.value)
        activity.seen(1)
        assertEquals(emptySet<Badge>(), activity.badges.value[1])
        assertEquals(emptySet<Int>(), activity.unseen.value)
    }

    // ---- Polling ----

    @Test
    fun `the first poll only records a baseline`() {
        activity.listed(org, listOf(pr(1)))
        val news = activity.polled(repoKey, mapOf(1 to state(1, mapOf(comment(5)))))
        assertTrue(news.isEmpty())
        assertTrue(activity.hasBaseline)
        assertEquals(emptySet<Int>(), activity.unseen.value)
    }

    @Test
    fun `a later poll reports new comments, marks the PR unseen and badges it`() {
        snapshots[1] = seen()
        activity.listed(org, listOf(pr(1)))
        activity.polled(repoKey, mapOf(1 to state(1)))
        val news = activity.polled(repoKey, mapOf(1 to state(1, mapOf(comment(5)))))
        assertEquals(1, news.single().state.prId)
        assertTrue(news.single().events.single() is ActivityEvent.Commented)
        assertEquals(setOf(1), activity.unseen.value)
        assertEquals(setOf(Badge.NEW_ACTIVITY), activity.badges.value[1])
    }

    @Test
    fun `being added to an unlisted PR asks the list to reload`() = runBlocking {
        activity.listed(org, listOf(pr(1)))
        activity.polled(repoKey, mapOf(1 to state(1)))
        val reload = async { activity.reloads.first() }
        yield()
        val news = activity.polled(repoKey, mapOf(1 to state(1), 2 to state(2)))
        reload.await()
        assertEquals(ActivityEvent.AddedAsReviewer, news.single().events.single())
        assertNull(activity.badges.value[2])
        assertEquals(setOf(2), activity.unseen.value)
    }

    @Test
    fun `closed PRs drop out of unseen`() {
        activity.polled(repoKey, mapOf(1 to state(1)))
        activity.polled(repoKey, mapOf(1 to state(1, mapOf(comment(5)))))
        activity.polled(repoKey, emptyMap())
        assertEquals(emptySet<Int>(), activity.unseen.value)
    }

    @Test
    fun `switching repo or turning polling off starts a new baseline`() {
        activity.polled(repoKey, mapOf(1 to state(1)))
        assertTrue(activity.polled("other/repo/x", mapOf(1 to state(1, mapOf(comment(5))))).isEmpty())
        activity.resetBaseline()
        assertTrue(activity.polled("other/repo/x", mapOf(1 to state(1, mapOf(comment(6))))).isEmpty())
    }

    @Test
    fun `a new list drops polled badges, keeping what last-seen says`() {
        snapshots[1] = seen()
        activity.listed(org, listOf(pr(1)))
        activity.polled(repoKey, mapOf(1 to state(1)))
        activity.polled(repoKey, mapOf(1 to state(1, mapOf(comment(5)))))
        activity.listed(org, listOf(pr(1)))
        assertEquals(emptySet<Badge>(), activity.badges.value[1])
        assertEquals("the dot stays until the PR is opened", setOf(1), activity.unseen.value)
    }

    // ---- Selection ----

    @Test
    fun `a selection waits for the list and is taken once`() {
        activity.select(7, threadId = 3)
        assertEquals(SelectRequest(7, 3), activity.selects.value)
        assertEquals(SelectRequest(7, 3), activity.takeSelect())
        assertNull(activity.takeSelect())
        assertNull(activity.selects.value)
    }
}
