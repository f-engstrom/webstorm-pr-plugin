package se.personal.adoprreview.state

import se.personal.adoprreview.AdoNotifications
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import se.personal.adoprreview.api.AdoClient
import se.personal.adoprreview.api.PrFilter
import se.personal.adoprreview.conversation.AdoThreadSource
import se.personal.adoprreview.conversation.MentionNames
import se.personal.adoprreview.conversation.PrConversations
import se.personal.adoprreview.api.dto.ChangeEntry
import se.personal.adoprreview.api.dto.Iteration
import se.personal.adoprreview.api.dto.IterationContext
import se.personal.adoprreview.api.dto.PullRequest
import se.personal.adoprreview.api.dto.Vote
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
class PrReviewService(private val project: Project, val scope: CoroutineScope) {

    /** The authenticated user's ID per org. */
    private val myIds = ConcurrentHashMap<String, String>()

    suspend fun myId(org: String): String =
        myIds[org] ?: AdoClient.getInstance().me(org).authenticatedUser.id.also { myIds[org] = it }

    /** For the Status tab: org to my identity ID, as resolved this session. */
    fun knownIdentities(): Map<String, String> = myIds.toMap()

    private val source = AdoThreadSource()

    /** `@<GUID>` mention names, shared by PR descriptions and comments. */
    private val mentions = MentionNames(source::displayNames)

    /** Review threads: loading, posting, replying, likes, edits, status, and going to a thread. */
    val conversations = PrConversations(source, mentions)

    /** List badges, unseen polled activity, and requests to the list (select a PR, reload). */
    val activity = PrActivity { org, prId -> LastSeenService.getInstance(project).get(LastSeen.key(org, prId)) }

    fun cachedNameCount(): Int = mentions.cachedCount

    fun loadedThreadPrCount(): Int = conversations.loadedCount

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
        val pr = mentions.resolve(remote.org, pullRequest.await())
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

    suspend fun vote(remote: AdoRemote, prId: Int, vote: Vote) {
        AdoClient.getInstance().vote(remote, prId, myId(remote.org), vote)
    }

    /**
     * Runs an operation the user started (a click, an action) on the EDT. A failure, other than cancellation, runs
     * [onFailure] and then shows an error notification titled [errorTitle]; [onFinally] runs either way.
     */
    fun launchOperation(
        errorTitle: String,
        onFailure: (Exception) -> Unit = {},
        onFinally: () -> Unit = {},
        block: suspend CoroutineScope.() -> Unit,
    ): Job = scope.launch(Dispatchers.EDT) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(e)
            AdoNotifications.error(project, errorTitle, e)
        } finally {
            onFinally()
        }
    }

    companion object {
        fun getInstance(project: Project): PrReviewService = project.service()
    }
}
