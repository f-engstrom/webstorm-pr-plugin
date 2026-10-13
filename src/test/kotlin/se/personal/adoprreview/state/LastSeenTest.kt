package se.personal.adoprreview.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.personal.adoprreview.api.dto.ChangeEntry
import se.personal.adoprreview.api.dto.ChangeItem
import se.personal.adoprreview.api.dto.Comment
import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.api.dto.CommitRef
import se.personal.adoprreview.api.dto.IdentityRef
import se.personal.adoprreview.api.dto.PullRequest
import java.time.Instant

class LastSeenTest {

    private val pr = PullRequest(pullRequestId = 1, lastMergeSourceCommit = CommitRef("bbb"))

    @Test
    fun `unseen PR is new`() {
        assertEquals(setOf(Badge.NEW), LastSeen.badges(pr, null))
    }

    @Test
    fun `new source commit means new commits`() {
        assertEquals(setOf(Badge.NEW_COMMITS), LastSeen.badges(pr, Snapshot(iterationId = 1, sourceCommit = "aaa")))
        assertEquals(emptySet<Badge>(), LastSeen.badges(pr, Snapshot(iterationId = 2, sourceCommit = "bbb")))
    }

    @Test
    fun `activity ignores my own and system comments`() {
        val threads = listOf(
            thread(comment("me", "2026-10-12T10:00:00Z"), comment("other", "2026-10-11T09:00:00Z")),
            thread(comment("other", "2026-10-12T12:00:00Z", type = "system")),
        )
        assertEquals(Instant.parse("2026-10-11T09:00:00Z"), LastSeen.latestActivityByOthers(threads, "ME"))
        assertNull(LastSeen.latestActivityByOthers(threads.take(1).map { it.copy(comments = it.comments.take(1)) }, "me"))
    }

    @Test
    fun `new activity compares against the snapshot`() {
        val seen = Snapshot(iterationId = 1, lastThreadActivity = "2026-10-11T09:00:00Z")
        assertTrue(LastSeen.hasNewActivity(seen, Instant.parse("2026-10-11T09:00:01Z")))
        assertFalse(LastSeen.hasNewActivity(seen, Instant.parse("2026-10-11T09:00:00Z")))
        assertFalse(LastSeen.hasNewActivity(seen, null))
        assertTrue(LastSeen.hasNewActivity(Snapshot(iterationId = 1), Instant.EPOCH))
    }

    @Test
    fun `viewed mark is tied to the file's blob`() {
        val edited = ChangeEntry(item = ChangeItem(path = "/a.ts", objectId = "obj2", originalObjectId = "obj1"), changeType = "edit")
        val deleted = ChangeEntry(item = ChangeItem(path = "/b.ts", originalObjectId = "obj3"), changeType = "delete")
        assertEquals("obj2", LastSeen.viewKey(edited))
        assertEquals("deleted:obj3", LastSeen.viewKey(deleted))
    }

    private fun thread(vararg comments: Comment) = CommentThread(id = 1, comments = comments.toList())

    private fun comment(author: String, date: String, type: String = "text") =
        Comment(id = 1, author = IdentityRef(id = author), publishedDate = date, commentType = type)
}
