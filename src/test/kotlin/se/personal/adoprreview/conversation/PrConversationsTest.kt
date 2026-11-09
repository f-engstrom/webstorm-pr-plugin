package se.personal.adoprreview.conversation

import com.intellij.diff.util.Side
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.personal.adoprreview.api.dto.Comment
import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.api.dto.FilePosition
import se.personal.adoprreview.api.dto.IdentityRef
import se.personal.adoprreview.api.dto.IterationContext
import se.personal.adoprreview.api.dto.NewThread
import se.personal.adoprreview.api.dto.ThreadContext
import se.personal.adoprreview.api.dto.ThreadStatus
import se.personal.adoprreview.repo.AdoRemote

class PrConversationsTest {

    private val guid = "11111111-2222-3333-4444-555555555555"

    /** An in-memory ADO: threads live in a list; writes change it like the server would. */
    private class FakeAdo : ThreadSource {
        var threads = mutableListOf<CommentThread>()
        val calls = mutableListOf<String>()
        val names = mutableMapOf<String, String>()
        var lookups = 0
        /** When set, the next `threads` call waits for it, to let another fetch overtake. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun threads(pr: PrRef): List<CommentThread> {
            val snapshot = threads.toList()
            gate?.let { gate = null; it.await() }
            return snapshot
        }
        override suspend fun create(pr: PrRef, thread: NewThread) {
            calls += "create"
            val id = (threads.maxOfOrNull { it.id } ?: 0) + 1
            threads += CommentThread(id, "active", thread.threadContext, comments = listOf(Comment(id * 10, content = thread.comments.single().content)))
        }
        override suspend fun reply(pr: PrRef, threadId: Int, parentCommentId: Int, content: String) {
            calls += "reply $threadId parent=$parentCommentId"
            threads.replaceAll { if (it.id == threadId) it.copy(comments = it.comments + Comment(it.comments.maxOf { c -> c.id } + 1, parentCommentId, content = content)) else it }
        }
        override suspend fun setLiked(pr: PrRef, threadId: Int, commentId: Int, liked: Boolean) { calls += "like $threadId/$commentId $liked" }
        override suspend fun edit(pr: PrRef, threadId: Int, commentId: Int, content: String) { calls += "edit $threadId/$commentId" }
        override suspend fun delete(pr: PrRef, threadId: Int, commentId: Int) { calls += "delete $threadId/$commentId" }
        override suspend fun setStatus(pr: PrRef, threadId: Int, status: ThreadStatus) {
            calls += "status $threadId ${status.value}"
            threads.replaceAll { if (it.id == threadId) it.copy(status = status.value) else it }
        }
        override suspend fun displayNames(org: String, ids: Set<String>): Map<String, String> {
            lookups++
            return names.filterKeys { it in ids }
        }
    }

    private val ado = FakeAdo()
    private val conversations = PrConversations(ado, MentionNames(ado::displayNames))
    private val pr = PrRef(AdoRemote("org", "project", "repo"), 42)

    private fun thread(id: Int, vararg comments: Comment, context: ThreadContext? = ThreadContext("/a.ts", FilePosition(3), FilePosition(5))) =
        CommentThread(id, "active", context, comments = comments.toList())

    private fun comment(id: Int, text: String = "hi", author: String = "u1") = Comment(id, content = text, author = IdentityRef(author, "User $author"))

    // ---- Anchors ----

    @Test
    fun `a source-side anchor becomes 1-based right positions`() {
        val anchor = LineAnchor("/src/a.ts", Side.RIGHT, startLine = 4, startColumn = 0, endLine = 6, endColumn = 12, changeTrackingId = 7)
        val thread = Anchors.newThread(anchor, "Nit", IterationContext(1, 3))
        val context = thread.threadContext!!
        assertEquals(FilePosition(5, 1), context.rightFileStart)
        assertEquals(FilePosition(7, 13), context.rightFileEnd)
        assertNull(context.leftFileStart)
        assertEquals("/src/a.ts", context.filePath)
        assertEquals(7, thread.pullRequestThreadContext!!.changeTrackingId)
        assertEquals(IterationContext(1, 3), thread.pullRequestThreadContext!!.iterationContext)
        assertEquals("active", thread.status)
        assertEquals(Anchors.TEXT, thread.comments.single().commentType)
        assertEquals(0, thread.comments.single().parentCommentId)
    }

    @Test
    fun `a target-side anchor becomes left positions on the PR path`() {
        val anchor = LineAnchor("/src/a.ts", Side.LEFT, 0, 0, 0, 3, changeTrackingId = null)
        val context = Anchors.newThread(anchor, "Why?", IterationContext(1, 1)).threadContext!!
        assertEquals(FilePosition(1, 1), context.leftFileStart)
        assertNull(context.rightFileStart)
        assertEquals("/src/a.ts", context.filePath)
    }

    @Test
    fun `a posted anchor maps back to the same side and line`() {
        val anchor = LineAnchor("/a.ts", Side.LEFT, 2, 0, 9, 4, null)
        val wire = Anchors.newThread(anchor, "x", IterationContext(1, 1))
        assertEquals(Side.LEFT to 9, Anchors.position(CommentThread(1, threadContext = wire.threadContext)))
    }

    @Test
    fun `thread positions use the end line, or the start when there is no end`() {
        assertEquals(Side.RIGHT to 4, Anchors.position(thread(1, context = ThreadContext("/a", rightFileStart = FilePosition(3), rightFileEnd = FilePosition(5)))))
        assertEquals(Side.LEFT to 2, Anchors.position(thread(1, context = ThreadContext("/a", leftFileStart = FilePosition(3)))))
        assertNull(Anchors.position(thread(1, context = null)))
        assertNull(Anchors.position(thread(1, context = ThreadContext("/a"))))
    }

    // ---- Store ----

    @Test
    fun `refresh shows review threads only`() = runBlocking {
        ado.threads += thread(1, comment(10))
        ado.threads += thread(2, Comment(20, content = "Pushed", commentType = "system"), context = null)
        ado.threads += thread(3, comment(30)).copy(isDeleted = true)
        conversations.refresh(pr)
        assertEquals(listOf(1), conversations.threads(42).value!!.map { it.id })
    }

    @Test
    fun `posting creates the thread and reloads, so views show it`() = runBlocking {
        conversations.refresh(pr)
        conversations.post(pr, LineAnchor("/a.ts", Side.RIGHT, 0, 0, 0, 1, null), "New", IterationContext(1, 1))
        assertEquals(listOf("New"), conversations.threads(42).value!!.map { it.comments.single().content })
    }

    @Test
    fun `replies go under the first comment`() = runBlocking {
        ado.threads += thread(1, comment(12), comment(11), comment(15))
        conversations.reply(pr, ado.threads.single(), "Agreed")
        assertEquals(listOf("reply 1 parent=11"), ado.calls)
        assertEquals(4, conversations.threads(42).value!!.single().comments.size)
    }

    @Test
    fun `every write reloads`() = runBlocking {
        ado.threads += thread(1, comment(10))
        conversations.setStatus(pr, 1, ThreadStatus.FIXED)
        assertEquals(ThreadStatus.FIXED, conversations.threads(42).value!!.single().threadStatus)
    }

    @Test
    fun `a background fetch doesn't keep PRs nobody opened`() = runBlocking {
        ado.threads += thread(1, comment(10))
        assertEquals(1, conversations.latest(pr).size)
        assertNull(conversations.threads(42).value)
        assertEquals(0, conversations.loadedCount)
    }

    @Test
    fun `a background fetch updates a PR that is open`() = runBlocking {
        conversations.refresh(pr)
        ado.threads += thread(1, comment(10))
        conversations.latest(pr)
        assertEquals(1, conversations.threads(42).value!!.size)
    }

    @Test
    fun `an older fetch finishing last doesn't hide a newer comment`() = runBlocking {
        conversations.refresh(pr)
        val gate = CompletableDeferred<Unit>()
        ado.gate = gate
        // A poll starts, reading the threads before the comment is posted, and stalls.
        val poll = async { conversations.latest(pr) }
        yield()
        conversations.post(pr, LineAnchor("/a.ts", Side.RIGHT, 0, 0, 0, 1, null), "Mine", IterationContext(1, 1))
        gate.complete(Unit)
        poll.await()
        assertEquals(1, conversations.threads(42).value!!.size)
    }

    @Test
    fun `mentions are resolved, keeping the raw text for editing`() = runBlocking {
        ado.names[guid] = "Ada"
        ado.threads += thread(1, comment(10, "Ping @<$guid>"))
        conversations.refresh(pr)
        val comment = conversations.threads(42).value!!.single().comments.single()
        assertEquals("Ping **@Ada**", comment.content)
        assertEquals("Ping @<$guid>", comment.rawContent)
    }

    @Test
    fun `mention names are looked up once and also come from authors`() = runBlocking {
        ado.threads += thread(1, comment(10, "cc @<$guid>", author = guid))
        conversations.refresh(pr)
        conversations.refresh(pr)
        assertEquals(0, ado.lookups)
        assertTrue(conversations.threads(42).value!!.single().comments.single().content!!.contains("@User $guid"))
    }

    @Test
    fun `unknown mentions say so`() = runBlocking {
        ado.threads += thread(1, comment(10, "@<$guid>"))
        conversations.refresh(pr)
        assertEquals("**@unknown user**", conversations.threads(42).value!!.single().comments.single().content)
    }

    @Test
    fun `a reveal is taken once, only by a view showing that thread`() {
        conversations.requestReveal(42, 3)
        assertNull(conversations.takeReveal(41, listOf(3)))
        assertNull(conversations.takeReveal(42, listOf(1, 2)))
        assertEquals(3, conversations.takeReveal(42, listOf(3)))
        assertNull(conversations.takeReveal(42, listOf(3)))
    }
}
