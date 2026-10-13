package se.personal.adoprreview.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import se.personal.adoprreview.AdoNotifications
import se.personal.adoprreview.actions.CheckoutAction
import se.personal.adoprreview.actions.EditPrAction
import se.personal.adoprreview.actions.VoteAction
import se.personal.adoprreview.api.dto.ChangeEntry
import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.api.dto.Iteration
import se.personal.adoprreview.api.dto.PullRequest
import se.personal.adoprreview.api.dto.Reviewer
import se.personal.adoprreview.api.dto.Vote
import se.personal.adoprreview.diff.PrDiffContext
import se.personal.adoprreview.diff.PrDiffOpener
import se.personal.adoprreview.diff.ThreadPanel
import se.personal.adoprreview.repo.ResolvedRepo
import se.personal.adoprreview.state.LastSeen
import se.personal.adoprreview.state.LastSeenService
import se.personal.adoprreview.state.PrDetails
import se.personal.adoprreview.state.PrReviewService
import se.personal.adoprreview.state.Snapshot
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/** Title, branches, reviewers, description and changed files of the selected PR. */
class PrDetailsPanel(
    private val project: Project,
    parentDisposable: Disposable,
    /** Called after the PR was changed here (vote, edit, publish), to reload list and details. */
    private val onChanged: () -> Unit,
    /** Called once the user has seen a PR, so the list can drop its badges. */
    private val onSeen: (prId: Int) -> Unit,
) : JPanel(CardLayout()) {

    private val message = JBLabel("", SwingConstants.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }

    private val title = Markdown.createPane().apply { font = JBFont.h4().asBold() }
    private val branches = SimpleColoredComponent().apply { ipad = JBUI.emptyInsets() }
    private val voteButton = VoteAction(project, onChanged)
    private val checkoutButton = JButton("Checkout", AllIcons.Vcs.Branch)
    private val publishButton = JButton("Publish").apply { toolTipText = "Publish this draft so reviewers are notified" }
    private val editButton = JButton("Edit…", AllIcons.Actions.Edit).apply { toolTipText = "Edit title, description and draft state" }
    private val reviewers = JPanel(VerticalLayout(JBUI.scale(2))).apply { isOpaque = false }
    private val description = Markdown.createPane()
    private val generalThreads = JPanel(VerticalLayout(JBUI.scale(6))).apply { isOpaque = false }
    private val generalExpanded = mutableMapOf<Int, Boolean>()
    private val generalDrafts = mutableMapOf<Int, String>()

    /** PR ID to thread ID to go to once that PR's threads are loaded (from a notification's "Show"). */
    private var pendingReveal: Pair<Int, Int>? = null
    private val fileTree = PrFileTree(::openDiff, ::onViewedChange, onOpenThread = { thread ->
        current?.let { revealInDiff(it.details, thread) }
    })
    private val baseCombo = ComboBox<Int>()
    private val headCombo = ComboBox<Int>()
    private val tabs = JBTabbedPane()
    private val allFilesButton = JButton("All Files", AllIcons.Actions.ListFiles).apply {
        toolTipText = "Show every changed file in one scrolling view, like the Files page in Azure DevOps"
    }

    private var loadJob: Job? = null
    private var rangeJob: Job? = null
    private var current: PrDiffContext? = null

    /** Paths changed since the previous visit; only meaningful for the full-PR range. */
    private var updatedSinceSeen: Set<String> = emptySet()

    /** Set while the combo boxes are filled programmatically, so that doesn't trigger a reload. */
    private var updatingCombos = false
    private var iterationDescriptions: Map<Int, String?> = emptyMap()

    init {
        val overview = JPanel(VerticalLayout(JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(8, 10)
            add(sectionLabel("Reviewers"))
            add(reviewers)
            add(sectionLabel("Description"))
            add(description)
            add(sectionLabel("Comments"))
            add(generalThreads)
        }
        // Wraps onto a second line in a narrow tool window instead of clipping; All Files first so it's always visible.
        val rangePicker = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(4, 6, 2, 6)
            add(allFilesButton)
            add(JBLabel("Compare"))
            add(baseCombo)
            add(JBLabel("→"))
            add(headCombo)
        }
        val files = JPanel(BorderLayout()).apply {
            add(rangePicker, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(fileTree, true), BorderLayout.CENTER)
        }
        tabs.addTab("Overview", ScrollPaneFactory.createScrollPane(overview, true))
        tabs.addTab("Files", files)

        val header = JPanel(VerticalLayout(JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(8, 10, 4, 10)
            add(title)
            add(branches)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(voteButton)
                add(publishButton)
                add(editButton)
                add(checkoutButton)
            })
        }
        val content = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }

        for (combo in listOf(baseCombo, headCombo)) {
            // Fixed width: update descriptions can be long; the popup still shows them in full.
            combo.setMinimumAndPreferredWidth(JBUI.scale(130))
            combo.renderer = textListCellRenderer { id -> id?.let { iterationLabel(it) } }
            combo.addActionListener { if (!updatingCombos) reloadRange() }
        }
        publishButton.addActionListener {
            val context = current ?: return@addActionListener
            EditPrAction.publish(project, context.repo.remote, context.details.pullRequest.pullRequestId, onChanged)
        }
        editButton.addActionListener {
            val context = current ?: return@addActionListener
            EditPrAction.edit(project, context.repo.remote, context.details.pullRequest.pullRequestId, onChanged)
        }
        allFilesButton.addActionListener {
            val context = current ?: return@addActionListener
            PrDiffOpener.openAll(project, context, fileTree.files)
        }
        checkoutButton.addActionListener {
            val context = current ?: return@addActionListener
            CheckoutAction.checkout(project, context.repo, context.details.pullRequest)
        }

        add(message, MESSAGE)
        add(content, CONTENT)
        showMessage("Select a pull request")
        Disposer.register(parentDisposable) { loadJob?.cancel() }
    }

    /** After [prId] loads, opens thread [threadId]: its diff for file comments, else the Overview tab. */
    fun revealWhenLoaded(prId: Int, threadId: Int?) {
        pendingReveal = threadId?.let { prId to it }
    }

    fun show(repo: ResolvedRepo?, pr: PullRequest?) {
        loadJob?.cancel()
        rangeJob?.cancel()
        current = null
        generalExpanded.clear()
        generalDrafts.clear()
        updatedSinceSeen = emptySet()
        if (repo == null || pr == null) {
            showMessage("Select a pull request")
            return
        }
        showMessage("Loading #${pr.pullRequestId}…")
        val service = PrReviewService.getInstance(project)
        loadJob = service.scope.launch(Dispatchers.EDT) {
            try {
                val details = service.loadDetails(repo.remote, pr.pullRequestId)
                current = PrDiffContext(repo, details)
                render(details)
                markSeen(this, repo, details)
                // Child of loadJob, so selecting another PR stops it.
                launch {
                    service.threads(pr.pullRequestId).collect { threads -> renderThreads(repo, details, threads) }
                }
                try {
                    service.reloadThreads(repo.remote, pr.pullRequestId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AdoNotifications.error(project, "Couldn't load comments for #${pr.pullRequestId}", e)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Couldn't load #${pr.pullRequestId}")
                AdoNotifications.error(project, "Couldn't load pull request #${pr.pullRequestId}", e)
            }
        }
    }

    /**
     * Saves a new snapshot for the PR, keeping the previous one long enough to flag files changed since then.
     * Thread activity is added to the snapshot as threads load (see [renderThreads]).
     */
    private fun markSeen(scope: CoroutineScope, repo: ResolvedRepo, details: PrDetails) {
        val prId = details.pullRequest.pullRequestId
        val key = LastSeen.key(repo.remote.org, prId)
        val lastSeen = LastSeenService.getInstance(project)
        val previous = lastSeen.get(key)
        lastSeen.put(key, Snapshot(
            iterationId = details.latestIteration,
            sourceCommit = details.pullRequest.lastMergeSourceCommit?.commitId,
            lastThreadActivity = previous?.lastThreadActivity,
            viewedFiles = previous?.viewedFiles.orEmpty(),
        ))
        onSeen(prId)
        renderViewState()

        if (previous != null && previous.iterationId < details.latestIteration) {
            scope.launch {
                try {
                    updatedSinceSeen = PrReviewService.getInstance(project).changedSince(repo.remote, details, previous.iterationId)
                    renderViewState()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Only a hint; the full file list is still shown.
                }
            }
        }
    }

    private fun onViewedChange(change: ChangeEntry, viewed: Boolean) {
        val context = current ?: return
        val key = LastSeen.key(context.repo.remote.org, context.details.pullRequest.pullRequestId)
        LastSeenService.getInstance(project).update(key) { snapshot ->
            snapshot.copy(
                viewedFiles = if (viewed) snapshot.viewedFiles + (change.path to LastSeen.viewKey(change))
                else snapshot.viewedFiles - change.path,
            )
        }
    }

    private fun renderViewState() {
        val context = current ?: return
        val key = LastSeen.key(context.repo.remote.org, context.details.pullRequest.pullRequestId)
        val viewed = LastSeenService.getInstance(project).get(key)?.viewedFiles.orEmpty()
        fileTree.setViewState(viewed, if (context.details.isFullRange) updatedSinceSeen else emptySet())
    }

    private fun reloadRange() {
        val context = current ?: return
        val base = baseCombo.item ?: return
        val head = headCombo.item ?: return
        if (base >= head) {
            fileTree.setChanges(emptyList())
            fileTree.emptyText.text = "Pick a base before the head update"
            return
        }
        rangeJob?.cancel()
        fileTree.emptyText.text = "Loading…"
        fileTree.setChanges(emptyList())
        val service = PrReviewService.getInstance(project)
        rangeJob = service.scope.launch(Dispatchers.EDT) {
            try {
                val details = service.loadRange(context.repo.remote, context.details, base, head)
                current = PrDiffContext(context.repo, details)
                renderFiles(details)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fileTree.emptyText.text = "Couldn't load changes"
                AdoNotifications.error(project, "Couldn't load changes for update $base → $head", e)
            }
        }
    }

    private fun render(details: PrDetails) {
        val pr = details.pullRequest
        title.text = HtmlChunk.text(if (pr.isDraft) "[Draft] ${pr.title}" else pr.title).toString()

        branches.clear()
        branches.append("#${pr.pullRequestId}  ·  ${pr.createdBy.displayName}  ·  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        branches.append(pr.sourceBranch, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        branches.append("  →  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        branches.append(pr.targetBranch, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        current?.let { voteButton.bind(it.repo.remote, pr.pullRequestId, details.myVote) }
        checkoutButton.toolTipText = "Fetch and check out ${pr.sourceBranch}"
        publishButton.isVisible = pr.isDraft

        reviewers.removeAll()
        if (pr.reviewers.isEmpty()) reviewers.add(hint("No reviewers"))
        pr.reviewers.sortedWith(compareByDescending<Reviewer> { it.isRequired }.thenBy { it.displayName })
            .forEach { reviewer ->
                reviewers.add(SimpleColoredComponent().apply {
                    ipad = JBUI.emptyInsets()
                    append(reviewer.displayName)
                    if (reviewer.isRequired) append("  required", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    val vote = Vote.of(reviewer.vote)
                    append("  ${vote.label}", vote.attributes)
                })
            }

        val text = pr.description?.takeIf { it.isNotBlank() }
        description.text = if (text != null) Markdown.toHtml(text) else HtmlChunk.text("No description").wrapWith("i").toString()
        description.caretPosition = 0

        iterationDescriptions = details.iterations.associate { it.id to it.description }
        updatingCombos = true
        val ids = details.iterations.map(Iteration::id).sorted()
        baseCombo.removeAllItems()
        (listOf(0) + ids.dropLast(1)).forEach(baseCombo::addItem)
        headCombo.removeAllItems()
        ids.forEach(headCombo::addItem)
        baseCombo.item = 0
        headCombo.item = details.latestIteration
        updatingCombos = false

        renderFiles(details)

        (layout as CardLayout).show(this, CONTENT)
        revalidate()
        repaint()
    }

    private fun renderFiles(details: PrDetails) {
        fileTree.emptyText.text = "No changed files"
        fileTree.setChanges(details.changes)
        current?.orderedFiles = fileTree.files
        renderViewState()
        tabs.setTitleAt(1, "Files (${details.changes.size})")
    }

    private fun iterationLabel(id: Int): String =
        if (id == 0) "Merge base"
        else "Update $id" + iterationDescriptions[id]?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { " · ${it.take(40)}" }.orEmpty()

    private fun renderThreads(repo: ResolvedRepo, details: PrDetails, threads: List<CommentThread>?) {
        val prId = details.pullRequest.pullRequestId
        val reveal = pendingReveal?.takeIf { it.first == prId && threads != null }?.second
            ?.let { id -> threads?.firstOrNull { it.id == id } }
        if (reveal != null) pendingReveal = null
        if (reveal != null && revealInDiff(details, reveal)) return renderThreads(repo, details, threads, null)
        reveal?.let { generalExpanded[it.id] = true }
        renderThreads(repo, details, threads, reveal?.id)
    }

    /** Opens the diff at [thread]'s file and has the inlay expand and scroll to it. False if it's not a file thread here. */
    private fun revealInDiff(details: PrDetails, thread: CommentThread): Boolean {
        val path = thread.filePath ?: return false
        val index = fileTree.files.indexOfFirst { it.path == path }.takeIf { it >= 0 } ?: return false
        PrReviewService.getInstance(project).requestReveal(details.pullRequest.pullRequestId, thread.id)
        tabs.selectedIndex = 1
        openDiff(fileTree.files, index)
        return true
    }

    private fun renderThreads(repo: ResolvedRepo, details: PrDetails, threads: List<CommentThread>?, scrollTo: Int?) {
        val prId = details.pullRequest.pullRequestId
        fileTree.setThreads(threads.orEmpty())
        generalThreads.removeAll()
        val general = threads.orEmpty().filter { it.threadContext == null }
        var revealed: ThreadPanel? = null
        when {
            threads == null -> generalThreads.add(hint("Loading comments…"))
            general.isEmpty() -> generalThreads.add(hint("No general comments"))
            else -> general.forEach { thread ->
                generalThreads.add(ThreadPanel(
                    project, repo.remote, prId, thread,
                    expanded = generalExpanded[thread.id] ?: !thread.threadStatus.isResolved,
                    onExpandedChange = { generalExpanded[thread.id] = it },
                    draft = generalDrafts[thread.id].orEmpty(),
                    onDraftChange = { generalDrafts[thread.id] = it },
                ).also { if (thread.id == scrollTo) revealed = it })
            }
        }
        generalThreads.revalidate()
        generalThreads.repaint()
        revealed?.let { panel ->
            tabs.selectedIndex = 0
            // After layout, so the panel has its final bounds.
            SwingUtilities.invokeLater { panel.scrollRectToVisible(Rectangle(0, 0, panel.width, panel.height)) }
        }

        // Having seen the threads, record the latest activity by others so "New activity" clears.
        if (threads != null) {
            LastSeen.latestActivityByOthers(threads, details.myId)?.let { latest ->
                LastSeenService.getInstance(project).update(LastSeen.key(repo.remote.org, prId)) { snapshot ->
                    if (LastSeen.hasNewActivity(snapshot, latest)) snapshot.copy(lastThreadActivity = latest.toString()) else snapshot
                }
            }
            onSeen(prId)
        }
    }

    private fun hint(text: String) = JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }

    private fun openDiff(files: List<ChangeEntry>, index: Int) {
        val context = current ?: return
        PrDiffOpener.open(project, context, files, index)
    }

    private fun showMessage(text: String) {
        message.text = text
        (layout as CardLayout).show(this, MESSAGE)
    }

    private fun sectionLabel(text: String) = JBLabel(text).apply { font = JBFont.label().asBold() }

    private companion object {
        const val MESSAGE = "message"
        const val CONTENT = "content"
    }
}
