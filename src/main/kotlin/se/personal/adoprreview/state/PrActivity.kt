package se.personal.adoprreview.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.api.dto.PullRequest

/** What the user last saw of a PR. Production: [LastSeenService]; tests: a map. */
fun interface Snapshots {
    fun get(org: String, prId: Int): Snapshot?
}

/** Asks the PR list to select [prId] and, if given, go to thread [threadId]. */
data class SelectRequest(val prId: Int, val threadId: Int? = null)

/** A poll result worth a notification. */
data class PrNews(val state: PrActivityState, val events: List<ActivityEvent>)

/**
 * What's new on the PRs in the list, for the list badges and the dot on the tool window icon. Fed by the list
 * (loaded PRs, their threads), the background poll, and the details view (the user saw a PR); the tool window only
 * observes [badges], [unseen], [selects] and [reloads].
 *
 * Badges: [Badge.NEW] for a PR never opened, [Badge.NEW_COMMITS] for pushes since it was opened, [Badge.NEW_ACTIVITY]
 * for comments by others since then (from the list check) or anything the poll reports. Opening a PR clears its
 * activity badge and its dot.
 */
class PrActivity(private val snapshots: Snapshots) {

    private val badgeFlow = MutableStateFlow<Map<Int, Set<Badge>>>(emptyMap())
    val badges: StateFlow<Map<Int, Set<Badge>>> get() = badgeFlow

    /** PRs with polled activity the user hasn't opened yet; drives the dot on the tool window icon. */
    private val unseenFlow = MutableStateFlow<Set<Int>>(emptySet())
    val unseen: StateFlow<Set<Int>> get() = unseenFlow

    /** A list reload, when the poll finds a PR the list doesn't show yet (just added as reviewer). */
    private val reloadFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reloads: SharedFlow<Unit> get() = reloadFlow

    private val selectFlow = MutableStateFlow<SelectRequest?>(null)
    /** Pending selection, kept until the list takes it (the list may not exist yet when it's asked). */
    val selects: StateFlow<SelectRequest?> get() = selectFlow

    @Volatile private var org: String? = null
    @Volatile private var listed: Map<Int, PullRequest> = emptyMap()

    private var baseline: Map<Int, PrActivityState>? = null
    private var baselineRepo: String? = null

    val hasBaseline: Boolean get() = baseline != null

    // ---- The list ----

    /** The list now shows [prs] of [org]; badges start from what the user last saw. Polled badges are dropped. */
    fun listed(org: String, prs: List<PullRequest>) {
        this.org = org
        listed = prs.associateBy { it.pullRequestId }
        badgeFlow.value = prs.associate { it.pullRequestId to LastSeen.badges(it, snapshots.get(org, it.pullRequestId)) }
    }

    /**
     * Adds [Badge.NEW_ACTIVITY] to listed PRs that were opened before and have comments by others since. Needs each
     * PR's threads ([fetch]), a few at a time; a PR whose threads fail to load is skipped.
     */
    suspend fun checkThreads(myId: String, fetch: suspend (prId: Int) -> List<CommentThread>) {
        val org = org ?: return
        val limit = Semaphore(6)
        coroutineScope {
            for (prId in listed.keys) {
                val seen = snapshots.get(org, prId) ?: continue
                launch {
                    val threads = try {
                        limit.withPermit { fetch(prId) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return@launch
                    }
                    // Re-read the snapshot: the user may have opened the PR meanwhile.
                    val current = snapshots.get(org, prId) ?: seen
                    if (LastSeen.hasNewActivity(current, LastSeen.latestActivityByOthers(threads, myId))) add(prId, Badge.NEW_ACTIVITY)
                }
            }
        }
    }

    /** The user opened [prId] (its snapshot is updated): its activity badge and dot go. */
    fun seen(prId: Int) {
        unseenFlow.update { it - prId }
        val pr = listed[prId] ?: return
        val org = org ?: return
        badgeFlow.update { it + (prId to LastSeen.badges(pr, snapshots.get(org, prId))) }
    }

    /** Ask the list to select [prId] and optionally go to [threadId]. */
    fun select(prId: Int, threadId: Int? = null) {
        selectFlow.value = SelectRequest(prId, threadId)
    }

    /** The pending selection, consumed. */
    fun takeSelect(): SelectRequest? {
        val request = selectFlow.value ?: return null
        return if (selectFlow.compareAndSet(request, null)) request else null
    }

    // ---- The background poll ----

    /**
     * Compares a poll of [repoKey] with the previous one. The first poll (or the first after switching repo or turning
     * polling off) only records a baseline, so starting the IDE is quiet. Returns what to notify about.
     */
    fun polled(repoKey: String, states: Map<Int, PrActivityState>): List<PrNews> {
        val previous = baseline.takeIf { baselineRepo == repoKey }
        baseline = states
        baselineRepo = repoKey
        if (previous == null) return emptyList()

        unseenFlow.update { it intersect states.keys } // closed or completed PRs
        val news = states.values.mapNotNull { state ->
            Activity.events(previous[state.prId], state).takeIf { it.isNotEmpty() }?.let { PrNews(state, it) }
        }
        for ((state, events) in news) {
            unseenFlow.update { it + state.prId }
            val badge = if (ActivityEvent.AddedAsReviewer in events) Badge.NEW else Badge.NEW_ACTIVITY
            when {
                state.prId in listed -> add(state.prId, badge)
                badge == Badge.NEW -> reloadFlow.tryEmit(Unit)
            }
        }
        return news
    }

    /** Polling was turned off: the next poll records a new baseline. */
    fun resetBaseline() {
        baseline = null
    }

    private fun add(prId: Int, badge: Badge) {
        badgeFlow.update { it + (prId to (it[prId].orEmpty() + badge)) }
    }
}
