package se.personal.adoprreview.state

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.BadgeIconSupplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import se.personal.adoprreview.api.AdoClient
import se.personal.adoprreview.api.PrFilter
import se.personal.adoprreview.repo.AdoRepoResolver
import se.personal.adoprreview.repo.ResolvedRepo
import se.personal.adoprreview.toolwindow.PrListPanel
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Polls the project's ADO repo every few minutes for activity that concerns me (see [Activity]). Activity puts a
 * dot on the tool window icon, marks the PR in the list, and logs a notification (no popup by default: group
 * "ADO PR Activity" is display type NONE, changeable in Settings → Notifications). The first poll only records
 * a baseline, so starting the IDE is quiet.
 */
@Service(Service.Level.PROJECT)
class PrWatcher(private val project: Project, private val scope: CoroutineScope) {

    /** The open Pull Requests panel, for "Show"; set by the tool window factory. */
    @Volatile
    var listPanel: PrListPanel? = null

    private var job: Job? = null
    private var baseline: Map<Int, PrActivityState>? = null
    private var baselineRepo: String? = null
    private val pollLock = Mutex()

    /** What the watcher is doing, for the Status tab. */
    enum class Phase(val label: String) {
        STARTING("Starting"), WAITING("Waiting"), POLLING("Polling"), PAUSED_IDLE("Paused (computer idle)"), DISABLED("Turned off")
    }

    data class PollResult(val at: Instant, val duration: Duration, val error: String?, val watchedPrs: Int)

    @Volatile var phase: Phase = Phase.STARTING
        private set
    @Volatile var nextPoll: TimeMark? = null
        private set
    @Volatile var lastPoll: PollResult? = null
        private set

    val hasBaseline: Boolean get() = baseline != null
    val unseenCount: Int get() = unseen.size
    val interval: Duration get() = INTERVAL
    val idlePause: Duration get() = IDLE_PAUSE

    /** Polls right away (from the Status tab), unless a poll is already running. */
    fun pollNow() {
        scope.launch { runPoll() }
    }

    /** PRs with activity the user hasn't opened yet; drives the dot on the tool window icon. EDT only. */
    private val unseen = mutableSetOf<Int>()

    /** Called when the user opens a PR. */
    fun markSeen(prId: Int) {
        if (unseen.remove(prId)) updateIcon()
    }

    /**
     * Ticks every [TICK] and polls when a poll is due, unless nobody has touched the computer for [IDLE_PAUSE]
     * (e.g. left on overnight). Then due polls are skipped, and the first tick after the user is back polls at once.
     * Timers use a monotonic clock that stops while the Mac sleeps, so nothing happens during sleep either.
     */
    fun start() {
        if (job?.isActive == true) return
        nextPoll = TimeSource.Monotonic.markNow() + FIRST_POLL
        job = scope.launch {
            while (isActive) {
                delay(TICK)
                if (!AdoSettings.getInstance().backgroundNotifications) {
                    baseline = null
                    phase = Phase.DISABLED
                    continue
                }
                if (SystemIdle.idleTime() >= IDLE_PAUSE) {
                    phase = Phase.PAUSED_IDLE
                    continue
                }
                phase = Phase.WAITING
                if (nextPoll?.hasPassedNow() != false) runPoll()
            }
        }
    }

    private suspend fun runPoll() {
        if (!pollLock.tryLock()) return
        val started = TimeSource.Monotonic.markNow()
        phase = Phase.POLLING
        var error: String? = null
        try {
            poll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Usually offline or `az` logged out; the next manual action reports it properly.
            error = e.message ?: e.javaClass.simpleName
            thisLogger().info("Background PR poll failed: $error")
        } finally {
            lastPoll = PollResult(Instant.now(), started.elapsedNow(), error, baseline?.size ?: 0)
            nextPoll = TimeSource.Monotonic.markNow() + INTERVAL
            phase = Phase.WAITING // the next tick corrects this if turned off or idle
            pollLock.unlock()
        }
    }

    private suspend fun poll() {
        val repo = AdoRepoResolver.getInstance(project).resolve() ?: return
        val remote = repo.remote
        val service = PrReviewService.getInstance(project)
        val client = AdoClient.getInstance()
        val myId = service.myId(remote.org)

        val states = coroutineScope {
            val reviewing = async { client.listPullRequests(remote, PrFilter.REVIEWER, myId) }
            val mine = async { client.listPullRequests(remote, PrFilter.AUTHOR, myId) }
            val prs = (reviewing.await() + mine.await()).distinctBy { it.pullRequestId }
            val limit = Semaphore(4)
            prs.map { pr ->
                async {
                    limit.withPermit {
                        val threads = service.fetchThreads(remote, pr.pullRequestId)
                        // PRs open in details or diffs get new comments live.
                        withContext(Dispatchers.EDT) { service.publishThreadsIfOpened(pr.pullRequestId, threads) }
                        Activity.state(pr, threads, myId)
                    }
                }
            }.awaitAll()
        }.associateBy { it.prId }

        val repoKey = "${remote.org}/${remote.project}/${remote.repo}".lowercase()
        val previous = baseline.takeIf { baselineRepo == repoKey }
        baseline = states
        baselineRepo = repoKey
        if (previous == null) return

        withContext(Dispatchers.EDT) {
            unseen.retainAll(states.keys) // closed or completed PRs
            for (state in states.values) {
                val events = Activity.events(previous[state.prId], state)
                if (events.isEmpty()) continue
                unseen += state.prId
                val badge = if (ActivityEvent.AddedAsReviewer in events) Badge.NEW else Badge.NEW_ACTIVITY
                listPanel?.markActivity(state.prId, badge)
                notify(repo, state, events)
            }
            updateIcon()
        }
    }

    private fun updateIcon() {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.setIcon(badgeIcon.getLiveIndicatorIcon(unseen.isNotEmpty()))
    }

    private fun notify(repo: ResolvedRepo, state: PrActivityState, events: List<ActivityEvent>) {
        val lines = events.take(MAX_LINES).map { StringUtil.escapeXmlEntities(Activity.describe(it)) } +
            listOfNotNull(if (events.size > MAX_LINES) "and ${events.size - MAX_LINES} more" else null)
        // Newest comment per thread, newest thread last. One thread: Show goes there. Several: Show opens the PR,
        // and each thread gets its own "Go to" action.
        val threads = events.filterIsInstance<ActivityEvent.Commented>().associateBy { it.threadId }.values.toList()
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("ADO PR Activity")
            .createNotification(StringUtil.escapeXmlEntities("#${state.prId} ${state.title}"), lines.joinToString("<br>"), NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Show") { show(state.prId, threads.singleOrNull()?.threadId) })
        if (threads.size > 1) {
            threads.asReversed().take(MAX_LINES).forEach { comment ->
                notification.addAction(NotificationAction.createSimple(Activity.goToLabel(comment)) { show(state.prId, comment.threadId) })
            }
        }
        notification
            .addAction(NotificationAction.createSimple("Open in Browser") { BrowserUtil.browse(repo.remote.webUrl(state.prId)) })
            .notify(project)
    }

    /** Opens the tool window on the Pull Requests tab, selects [prId] and, if given, goes to thread [threadId]. */
    private fun show(prId: Int, threadId: Int?) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate {
            toolWindow.contentManager.getContent(0)?.let(toolWindow.contentManager::setSelectedContent)
            listPanel?.refreshAndSelect(prId, revealThread = threadId)
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "ADO PRs"

        /** The sandbox IDE sets this (see build.gradle.kts) to poll faster while testing. */
        private val OVERRIDE = Integer.getInteger("adoPrReview.pollIntervalSeconds")?.seconds
        private val FIRST_POLL = OVERRIDE ?: 1.minutes
        private val INTERVAL = OVERRIDE ?: 5.minutes
        private val TICK = minOf(30.seconds, INTERVAL)

        /** No keyboard or mouse input anywhere for this long pauses polling. */
        private val IDLE_PAUSE = 30.minutes
        private const val MAX_LINES = 5

        /** Same icon as the tool window in plugin.xml, with or without the activity dot. */
        private val badgeIcon = BadgeIconSupplier(AllIcons.Vcs.Merge)

        fun getInstance(project: Project): PrWatcher = project.service()
    }
}

class PrWatcherStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        PrWatcher.getInstance(project).start()
    }
}
