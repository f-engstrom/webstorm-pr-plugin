package se.personal.adoprreview.conversation

import com.intellij.diff.util.Side
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import se.personal.adoprreview.api.Mentions
import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.api.dto.FilePosition
import se.personal.adoprreview.api.dto.IdentityRef
import se.personal.adoprreview.api.dto.IterationContext
import se.personal.adoprreview.api.dto.NewComment
import se.personal.adoprreview.api.dto.NewThread
import se.personal.adoprreview.api.dto.PrThreadContext
import se.personal.adoprreview.api.dto.PullRequest
import se.personal.adoprreview.api.dto.ThreadContext
import se.personal.adoprreview.api.dto.ThreadStatus
import se.personal.adoprreview.api.dto.reviewThreads
import se.personal.adoprreview.repo.AdoRemote
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A pull request in a repo. PR IDs are unique per org, so views key on [id] alone. */
data class PrRef(val remote: AdoRemote, val id: Int)

/** The ADO thread endpoints. Production: [AdoThreadSource]; tests: an in-memory fake. */
interface ThreadSource {
    /** All threads, system and deleted ones included, mentions as `@<GUID>`. */
    suspend fun threads(pr: PrRef): List<CommentThread>
    suspend fun create(pr: PrRef, thread: NewThread)
    suspend fun reply(pr: PrRef, threadId: Int, parentCommentId: Int, content: String)
    suspend fun setLiked(pr: PrRef, threadId: Int, commentId: Int, liked: Boolean)
    suspend fun edit(pr: PrRef, threadId: Int, commentId: Int, content: String)
    suspend fun delete(pr: PrRef, threadId: Int, commentId: Int)
    suspend fun setStatus(pr: PrRef, threadId: Int, status: ThreadStatus)
    /** Display names by identity ID, for the IDs ADO knows. */
    suspend fun displayNames(org: String, ids: Set<String>): Map<String, String>
}

/**
 * Where a new comment goes: lines and columns of one side of a file in the PR, 0-based as in the editor. [Side.RIGHT]
 * is the source (new) side, [Side.LEFT] the target (old) side.
 */
data class LineAnchor(
    val path: String,
    val side: Side,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    /** ADO's ID for the file change; keeps the thread on its line after new pushes. */
    val changeTrackingId: Int?,
)

/** ADO's wire anchors (1-based, left/right positions) to and from editor lines. */
object Anchors {

    /** The thread's side and its last line (0-based), or null for a thread not on a line (PR-level). */
    fun position(thread: CommentThread): Pair<Side, Int>? {
        val context = thread.threadContext ?: return null
        return when {
            context.rightFileStart != null -> Side.RIGHT to (context.rightFileEnd ?: context.rightFileStart).line - 1
            context.leftFileStart != null -> Side.LEFT to (context.leftFileEnd ?: context.leftFileStart).line - 1
            else -> null
        }
    }

    fun newThread(anchor: LineAnchor, text: String, iterationContext: IterationContext): NewThread {
        val start = FilePosition(anchor.startLine + 1, anchor.startColumn + 1)
        val end = FilePosition(anchor.endLine + 1, anchor.endColumn + 1)
        // filePath is always the PR's (new) path; the side is given by left vs right positions.
        val context = if (anchor.side == Side.RIGHT) {
            ThreadContext(filePath = anchor.path, rightFileStart = start, rightFileEnd = end)
        } else {
            ThreadContext(filePath = anchor.path, leftFileStart = start, leftFileEnd = end)
        }
        return NewThread(
            comments = listOf(NewComment(text, parentCommentId = 0, commentType = TEXT)),
            status = ThreadStatus.ACTIVE.value,
            threadContext = context,
            pullRequestThreadContext = PrThreadContext(anchor.changeTrackingId, iterationContext),
        )
    }

    /** ADO's `commentType` for a normal text comment. */
    const val TEXT = 1
}

/** `@<GUID>` mentions to names, with a per-org cache of names seen in PR data or looked up. Best effort. */
class MentionNames(private val lookup: suspend (org: String, ids: Set<String>) -> Map<String, String>) {

    private val names = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    val cachedCount: Int get() = names.values.sumOf { it.size }

    suspend fun resolve(org: String, pr: PullRequest): PullRequest {
        val description = pr.description ?: return pr
        if (Mentions.ids(description).isEmpty()) return pr
        val known = pr.reviewers.map { IdentityRef(it.id, it.displayName) } + pr.createdBy
        return pr.copy(description = Mentions.replace(description, namesFor(org, known, listOf(description))))
    }

    /** Comments get their content with names, and the stored text (real mentions) as `rawContent`, for editing. */
    suspend fun resolve(org: String, threads: List<CommentThread>): List<CommentThread> {
        val contents = threads.flatMap { thread -> thread.comments.map { it.content } }
        if (contents.all { Mentions.ids(it).isEmpty() }) return threads
        val names = namesFor(org, threads.flatMap { thread -> thread.comments.map { it.author } }, contents)
        return threads.map { thread ->
            thread.copy(comments = thread.comments.map { comment ->
                comment.content?.let { comment.copy(content = Mentions.replace(it, names), rawContent = it) } ?: comment
            })
        }
    }

    private suspend fun namesFor(org: String, known: List<IdentityRef>, texts: List<String?>): Map<String, String> {
        val cache = names.computeIfAbsent(org.lowercase()) { ConcurrentHashMap() }
        known.filter { it.id.isNotEmpty() && it.displayName.isNotEmpty() }.forEach { cache[it.id.lowercase()] = it.displayName }
        val missing = texts.flatMap(Mentions::ids).toSet() - cache.keys
        if (missing.isNotEmpty()) {
            try {
                lookup(org, missing).forEach { (id, name) -> cache[id.lowercase()] = name }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unresolved mentions are shown as "unknown user".
            }
        }
        return cache
    }
}

/**
 * The review threads of the PRs open in this session, and every way to change them. Views observe [threads]; each
 * write reloads the PR's threads, so all views of it update. Threads come back with system threads removed and
 * mentions resolved, whoever fetched them (details, diff, list badges or the background poll).
 */
class PrConversations(private val source: ThreadSource, private val mentions: MentionNames) {

    private class Published {
        val flow = MutableStateFlow<List<CommentThread>?>(null)
        val started = AtomicLong()
        /** The [started] number of the fetch now in [flow]; an older fetch finishing later is dropped. */
        var shown = 0L
    }

    private val prs = ConcurrentHashMap<Int, Published>()

    private fun published(prId: Int) = prs.computeIfAbsent(prId) { Published() }

    /** Review threads of a PR; `null` until first loaded. */
    fun threads(prId: Int): StateFlow<List<CommentThread>?> = published(prId).flow

    val loadedCount: Int get() = prs.values.count { it.flow.value != null }

    /** Fetches and shows the PR's threads. */
    suspend fun refresh(pr: PrRef) {
        fetch(pr, publishAlways = true)
    }

    /** [refresh], unless they are already loaded. */
    suspend fun ensureLoaded(pr: PrRef) {
        if (threads(pr.id).value == null) refresh(pr)
    }

    /**
     * Fetches the PR's threads for a background check (list badges, polling) and returns them. Views that already
     * show the PR get them too; PRs nobody opened aren't kept.
     */
    suspend fun latest(pr: PrRef): List<CommentThread> = fetch(pr, publishAlways = false)

    suspend fun post(pr: PrRef, anchor: LineAnchor, text: String, iterationContext: IterationContext) =
        write(pr) { source.create(pr, Anchors.newThread(anchor, text, iterationContext)) }

    /** Replies go under the thread's first comment, as in the ADO web UI. */
    suspend fun reply(pr: PrRef, thread: CommentThread, text: String) =
        write(pr) { source.reply(pr, thread.id, thread.comments.minOf { it.id }, text) }

    suspend fun setLiked(pr: PrRef, threadId: Int, commentId: Int, liked: Boolean) =
        write(pr) { source.setLiked(pr, threadId, commentId, liked) }

    suspend fun edit(pr: PrRef, threadId: Int, commentId: Int, text: String) =
        write(pr) { source.edit(pr, threadId, commentId, text) }

    suspend fun delete(pr: PrRef, threadId: Int, commentId: Int) =
        write(pr) { source.delete(pr, threadId, commentId) }

    suspend fun setStatus(pr: PrRef, threadId: Int, status: ThreadStatus) =
        write(pr) { source.setStatus(pr, threadId, status) }

    private suspend fun write(pr: PrRef, action: suspend () -> Unit) {
        action()
        refresh(pr)
    }

    private suspend fun fetch(pr: PrRef, publishAlways: Boolean): List<CommentThread> {
        val state = published(pr.id)
        val number = state.started.incrementAndGet()
        val threads = mentions.resolve(pr.remote.org, source.threads(pr).reviewThreads())
        synchronized(state) {
            val opened = state.flow.value != null
            if ((publishAlways || opened) && number > state.shown) {
                state.shown = number
                state.flow.value = threads
            }
        }
        return threads
    }

    // ---- Going to a thread from a notification ----

    /**
     * A thread to expand and scroll to when a diff shows it (from a notification's "Show"), as (PR ID, thread ID).
     * A flow, so a diff that already shows the file reacts too.
     */
    private val pendingReveal = MutableStateFlow<Pair<Int, Int>?>(null)
    val reveals: StateFlow<Pair<Int, Int>?> get() = pendingReveal

    fun requestReveal(prId: Int, threadId: Int) {
        pendingReveal.value = prId to threadId
    }

    /** The pending thread for [prId] if it is one of [threadIds]; consumes it. */
    fun takeReveal(prId: Int, threadIds: Collection<Int>): Int? {
        val request = pendingReveal.value?.takeIf { it.first == prId && it.second in threadIds } ?: return null
        return if (pendingReveal.compareAndSet(request, null)) request.second else null
    }
}
