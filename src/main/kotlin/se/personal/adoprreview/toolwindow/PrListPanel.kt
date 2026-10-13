package se.personal.adoprreview.toolwindow

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import se.personal.adoprreview.AdoNotifications
import se.personal.adoprreview.actions.CreatePrAction
import se.personal.adoprreview.actions.FilterActionGroup
import se.personal.adoprreview.actions.OpenInBrowserAction
import se.personal.adoprreview.actions.RefreshAction
import se.personal.adoprreview.api.AdoClient
import se.personal.adoprreview.api.PrFilter
import se.personal.adoprreview.api.dto.PullRequest
import se.personal.adoprreview.api.dto.reviewThreads
import se.personal.adoprreview.repo.AdoRepoResolver
import se.personal.adoprreview.repo.ResolvedRepo
import se.personal.adoprreview.state.AdoSettings
import se.personal.adoprreview.state.Badge
import se.personal.adoprreview.state.LastSeen
import se.personal.adoprreview.state.LastSeenService
import se.personal.adoprreview.state.PrList
import se.personal.adoprreview.state.PrWatcher
import se.personal.adoprreview.state.PrReviewService
import java.awt.event.MouseEvent
import javax.swing.ListSelectionModel

class PrListPanel(private val project: Project, parentDisposable: Disposable) : SimpleToolWindowPanel(true, true) {

    private val model = CollectionListModel<PullRequest>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    private val renderer = PrCellRenderer()
    // After voting or editing, reload the list and, through reselection, the details.
    private val details = PrDetailsPanel(project, parentDisposable, onChanged = { refresh() }, onSeen = ::onSeen)
    private var loadJob: Job? = null

    var repo: ResolvedRepo? = null
        private set

    val selectedPullRequest: PullRequest? get() = list.selectedValue

    init {
        list.cellRenderer = renderer
        list.addListSelectionListener { if (!it.valueIsAdjusting) details.show(repo, selectedPullRequest) }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                openSelectedInBrowser()
                return true
            }
        }.installOn(list)

        val actions = DefaultActionGroup(RefreshAction(this), FilterActionGroup(this), OpenInBrowserAction(this), CreatePrAction(this))
        val toolbar = ActionManager.getInstance().createActionToolbar("AdoPrList", actions, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        setContent(OnePixelSplitter(true, "AdoPrReview.ListDetails", 0.4f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(list, true)
            secondComponent = details
        })

        // Git repositories load asynchronously, so retry when the mapping changes and nothing was found yet.
        project.messageBus.connect(parentDisposable).subscribe(
            VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED,
            VcsRepositoryMappingListener { invokeLater { if (repo == null) refresh() } },
        )
        Disposer.register(parentDisposable) { loadJob?.cancel() }
    }

    /** Reloads and selects [prId]; if the current filter hides it (e.g. your new PR under "Assigned to me"), switches to "Created by me". */
    fun refreshAndSelect(prId: Int, revealThread: Int? = null) {
        details.revealWhenLoaded(prId, revealThread)
        refresh(selectId = prId)
    }

    fun refresh(reloadUser: Boolean = false, selectId: Int? = null) {
        loadJob?.cancel()
        val resolved = AdoRepoResolver.getInstance(project).resolve()
        repo = resolved
        if (resolved == null) {
            model.removeAll()
            list.emptyText.text = if (ProjectLevelVcsManager.getInstance(project).hasActiveVcss()) {
                "No dev.azure.com remote found in this project"
            } else {
                "No Git repository found in this project"
            }
            return
        }

        val selectedId = selectId ?: selectedPullRequest?.pullRequestId
        list.emptyText.text = "Loading pull requests…"
        list.setPaintBusy(true)
        loadJob = PrReviewService.getInstance(project).scope.launch(Dispatchers.EDT) {
            var retryAsAuthor = false
            try {
                val result = PrReviewService.getInstance(project).loadPullRequests(resolved.remote, AdoSettings.getInstance().listFilter, reloadUser)
                renderer.myId = result.myId
                renderer.badges = result.pullRequests.associate { it.pullRequestId to LastSeen.badges(it, snapshot(resolved, it.pullRequestId)) }
                model.replaceAll(result.pullRequests)
                list.emptyText.text = "No active pull requests"
                val index = result.pullRequests.indexOfFirst { it.pullRequestId == selectedId }
                if (index >= 0) {
                    list.selectedIndex = index
                    list.ensureIndexIsVisible(index)
                } else if (selectId != null && AdoSettings.getInstance().listFilter == PrFilter.REVIEWER) {
                    retryAsAuthor = true
                }
                if (!retryAsAuthor) launch { checkActivity(resolved, result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.removeAll()
                list.emptyText.text = "Couldn't load pull requests"
                AdoNotifications.error(project, "Couldn't load pull requests", e)
            } finally {
                list.setPaintBusy(false)
            }
            // Outside the try, so the reload starts after this one has fully finished.
            if (retryAsAuthor) {
                AdoSettings.getInstance().listFilter = PrFilter.AUTHOR
                refresh(selectId = selectId)
            }
        }
    }

    /**
     * Adds "New activity" to PRs where someone else commented since the user last looked.
     * Needs each PR's threads, so it runs after the list is shown, a few requests at a time.
     */
    private suspend fun checkActivity(repo: ResolvedRepo, result: PrList) {
        val limit = Semaphore(6)
        coroutineScope {
            for (pr in result.pullRequests) {
                val seen = snapshot(repo, pr.pullRequestId) ?: continue
                launch {
                    val threads = try {
                        limit.withPermit { AdoClient.getInstance().threads(repo.remote, pr.pullRequestId).reviewThreads() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return@launch
                    }
                    // Re-read the snapshot: the user may have opened the PR meanwhile.
                    val current = snapshot(repo, pr.pullRequestId) ?: seen
                    if (LastSeen.hasNewActivity(current, LastSeen.latestActivityByOthers(threads, result.myId))) {
                        renderer.badges += pr.pullRequestId to (renderer.badges[pr.pullRequestId].orEmpty() + Badge.NEW_ACTIVITY)
                        list.repaint()
                    }
                }
            }
        }
    }

    /** Marks [prId] from background polling; a PR not in the list yet (just added as reviewer) triggers a reload. */
    fun markActivity(prId: Int, badge: Badge) {
        if (model.items.none { it.pullRequestId == prId }) {
            if (badge == Badge.NEW && loadJob?.isActive != true) refresh()
            return
        }
        renderer.badges += prId to (renderer.badges[prId].orEmpty() + badge)
        list.repaint()
    }

    private fun onSeen(prId: Int) {
        PrWatcher.getInstance(project).markSeen(prId)
        val repo = repo ?: return
        val pr = model.items.firstOrNull { it.pullRequestId == prId } ?: return
        renderer.badges += prId to LastSeen.badges(pr, snapshot(repo, prId))
        list.repaint()
    }

    private fun snapshot(repo: ResolvedRepo, prId: Int) =
        LastSeenService.getInstance(project).get(LastSeen.key(repo.remote.org, prId))

    fun openSelectedInBrowser() {
        val pr = selectedPullRequest ?: return
        val remote = repo?.remote ?: return
        BrowserUtil.browse(remote.webUrl(pr.pullRequestId))
    }
}
