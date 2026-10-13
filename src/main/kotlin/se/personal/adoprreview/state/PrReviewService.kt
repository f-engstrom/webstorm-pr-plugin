package se.personal.adoprreview.state

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import se.personal.adoprreview.api.AdoClient
import se.personal.adoprreview.api.Mentions
import se.personal.adoprreview.api.PrFilter
import se.personal.adoprreview.api.dto.ChangeEntry
import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.api.dto.IdentityRef
import se.personal.adoprreview.api.dto.Iteration
import se.personal.adoprreview.api.dto.IterationContext
import se.personal.adoprreview.api.dto.NewThread
import se.personal.adoprreview.api.dto.PullRequest
import se.personal.adoprreview.api.dto.ThreadStatus
import se.personal.adoprreview.api.dto.Vote
import se.personal.adoprreview.api.dto.reviewThreads
import se.personal.adoprreview.repo.AdoRemote
import java.util.concurrent.ConcurrentHashMap

class PrList(val pullRequests: List<PullRequest>, val myId: String)

/**
 * A PR and its changes for an iteration range: [baseIteration] (0 = merge base of source and target) to [headIteration].
 * The default range, 0 to latest, is the whole PR.
 */
class PrDetails(
    val pullRequest: PullRequest,
    val iterations: List<Iteration>,
    val baseIteration: Int,
    val headIteration: Int,
    val changes: List<ChangeEntry>,
    val myId: String,
) {
    val latestIteration: Int get() = iterations.maxOf { it.id }
    val isFullRange: Boolean get() = baseIteration == 0 && headIteration == latestIteration

    private val head: Iteration get() = iterations.first { it.id == headIteration }

    val myVote: Vote get() = Vote.of(pullRequest.voteOf(myId))

    /** Left side of the diff: the base iteration's source commit, or the merge base for the whole PR. */
    val baseCommit: String?
        get() = if (baseIteration == 0) head.commonRefCommit?.commitId
        else iterations.firstOrNull { it.id == baseIteration }?.sourceRefCommit?.commitId

    val sourceCommit: String? get() = head.sourceRefCommit?.commitId

    /** Sent with new threads so ADO knows which comparison they were made on. */
    val iterationContext: IterationContext
        get() = IterationContext(firstComparingIteration = if (baseIteration == 0) 1 else baseIteration, secondComparingIteration = headIteration)
}

@Service(Service.Level.PROJECT)
class PrReviewService(@Suppress("unused") private val project: Project, val scope: CoroutineScope) {

    /** The authenticated user's ID per org. */
    private val myIds = ConcurrentHashMap<String, String>()

    suspend fun myId(org: String): String =
        myIds[org] ?: AdoClient.getInstance().me(org).authenticatedUser.id.also { myIds[org] = it }

    /** For the Status tab: org to my identity ID, as resolved this session. */
    fun knownIdentities(): Map<String, String> = myIds.toMap()

    fun cachedNameCount(): Int = names.values.sumOf { it.size }

    fun loadedThreadPrCount(): Int = threadFlows.values.count { it.value != null }

    /** Display names per lower-cased identity ID, per org; filled from PR data and the identity service. */
    private val names = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    /** Replaces `@<GUID>` mentions in [texts] with names, looking up unknown IDs in one request. Best effort. */
    private suspend fun mentionNames(org: String, known: List<IdentityRef>, texts: List<String?>): Map<String, String> {
        val cache = names.computeIfAbsent(org.lowercase()) { ConcurrentHashMap() }
        known.filter { it.id.isNotEmpty() && it.displayName.isNotEmpty() }.forEach { cache[it.id.lowercase()] = it.displayName }
        val missing = texts.flatMap(Mentions::ids).toSet() - cache.keys
        if (missing.isNotEmpty()) {
            try {
                AdoClient.getInstance().identities(org, missing).forEach { identity ->
                    identity.displayName?.let { cache[identity.id.lowercase()] = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unresolved mentions are shown as "unknown user".
            }
        }
        return cache
    }

    private suspend fun withMentionNames(org: String, pr: PullRequest): PullRequest {
        val description = pr.description ?: return pr
        if (Mentions.ids(description).isEmpty()) return pr
        val known = pr.reviewers.map { IdentityRef(it.id, it.displayName) } + pr.createdBy
        return pr.copy(description = Mentions.replace(description, mentionNames(org, known, listOf(description))))
    }

    private suspend fun withMentionNames(org: String, threads: List<CommentThread>): List<CommentThread> {
        val contents = threads.flatMap { thread -> thread.comments.map { it.content } }
        if (contents.all { Mentions.ids(it).isEmpty() }) return threads
        val names = mentionNames(org, threads.flatMap { thread -> thread.comments.map { it.author } }, contents)
        return threads.map { thread ->
            thread.copy(comments = thread.comments.map { comment ->
                comment.content?.let { comment.copy(content = Mentions.replace(it, names)) } ?: comment
            })
        }
    }

    /** [reloadUser] re-fetches who `az` is logged in as, e.g. after switching accounts. */
    suspend fun loadPullRequests(remote: AdoRemote, filter: PrFilter, reloadUser: Boolean = false): PrList {
        if (reloadUser) myIds.remove(remote.org)
        val myId = myId(remote.org)
        return PrList(AdoClient.getInstance().listPullRequests(remote, filter, myId), myId)
    }

    /** The whole PR: merge base to latest iteration. */
    suspend fun loadDetails(remote: AdoRemote, prId: Int): PrDetails = coroutineScope {
        val client = AdoClient.getInstance()
        val pullRequest = async { client.pullRequest(remote, prId) }
        val myId = async { myId(remote.org) }
        val iterations = client.iterations(remote, prId)
        val latest = iterations.maxOf { it.id }
        val pr = withMentionNames(remote.org, pullRequest.await())
        PrDetails(pr, iterations, 0, latest, client.changes(remote, prId, latest, compareTo = 0), myId.await())
    }

    /** The same PR with changes for another iteration range. */
    suspend fun loadRange(remote: AdoRemote, details: PrDetails, base: Int, head: Int): PrDetails {
        val prId = details.pullRequest.pullRequestId
        val changes = AdoClient.getInstance().changes(remote, prId, head, compareTo = base)
        return PrDetails(details.pullRequest, details.iterations, base, head, changes, details.myId)
    }

    /** Paths changed between [sinceIteration] and the latest iteration. */
    suspend fun changedSince(remote: AdoRemote, details: PrDetails, sinceIteration: Int): Set<String> =
        AdoClient.getInstance().changes(remote, details.pullRequest.pullRequestId, details.latestIteration, compareTo = sinceIteration)
            .mapTo(mutableSetOf()) { it.path }

    private val threadFlows = ConcurrentHashMap<Int, MutableStateFlow<List<CommentThread>?>>()

    /** Review threads of a PR (system threads removed); `null` until first loaded. */
    fun threads(prId: Int): StateFlow<List<CommentThread>?> = threadFlow(prId)

    suspend fun reloadThreads(remote: AdoRemote, prId: Int) {
        threadFlow(prId).value = fetchThreads(remote, prId)
    }

    /** Updates [threads] with freshly fetched threads, but only for PRs opened this session (that have a flow value). */
    fun publishThreadsIfOpened(prId: Int, threads: List<CommentThread>) {
        val flow = threadFlows[prId] ?: return
        if (flow.value != null && flow.value != threads) flow.value = threads
    }

    /** Review threads of a PR with mentions resolved, without publishing them to [threads]. */
    suspend fun fetchThreads(remote: AdoRemote, prId: Int): List<CommentThread> =
        withMentionNames(remote.org, AdoClient.getInstance().threads(remote, prId).reviewThreads())

    suspend fun reply(remote: AdoRemote, prId: Int, thread: CommentThread, content: String) {
        val parent = thread.comments.minOf { it.id }
        AdoClient.getInstance().reply(remote, prId, thread.id, parent, content)
        reloadThreads(remote, prId)
    }

    suspend fun setThreadStatus(remote: AdoRemote, prId: Int, thread: CommentThread, status: ThreadStatus) {
        AdoClient.getInstance().setThreadStatus(remote, prId, thread.id, status)
        reloadThreads(remote, prId)
    }

    suspend fun createThread(remote: AdoRemote, prId: Int, thread: NewThread) {
        AdoClient.getInstance().createThread(remote, prId, thread)
        reloadThreads(remote, prId)
    }

    suspend fun vote(remote: AdoRemote, prId: Int, vote: Vote) {
        AdoClient.getInstance().vote(remote, prId, myId(remote.org), vote)
    }

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

    private fun threadFlow(prId: Int) = threadFlows.computeIfAbsent(prId) { MutableStateFlow(null) }

    companion object {
        fun getInstance(project: Project): PrReviewService = project.service()
    }
}
