package se.personal.adoprreview.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.ui.EditorNotificationPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.state.PrReviewService

/** Adds review threads, commenting and local-file banners to text diffs of PR files (single and all-files views). */
class PrDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val pr = request.getUserData(PrDiffKeys.PR_KEY) ?: return
        val path = request.getUserData(PrDiffKeys.PATH_KEY) ?: return
        val project = context.project ?: return
        val layout = DiffLayout.of(viewer) ?: return
        // Everything below lives as long as this viewer. Disposed from the viewer's onDispose, which also fires
        // when the all-files view drops a block that scrolled out (Disposer children of the viewer would leak then).
        val lifetime = Disposer.newDisposable("ADO PR diff viewer")
        layout.onDispose { Disposer.dispose(lifetime) }
        ThreadInlays(project, pr, path, lifetime, layout).start()

        // The right side may be the real project file; if so, comments there are only allowed while it matches the PR.
        val rightDocument = ((request as? ContentDiffRequest)?.contents?.getOrNull(1) as? DocumentContent)?.document
        val localSync = request.getUserData(PrDiffKeys.LOCAL_PR_TEXT_KEY)?.let { prText ->
            val bannerEditor = layout.bannerEditor(Side.RIGHT) ?: return@let null
            val document = rightDocument ?: return@let null
            val dirty = ChangeListManager.getInstance(project).allChanges.isNotEmpty()
            LocalFileSync(document, bannerEditor, prText, pr.details.pullRequest.pullRequestId, dirty).also { it.install(lifetime) }
        }

        if (request.getUserData(PrDiffKeys.LOCAL_CHANGES_KEY) == true) {
            layout.bannerEditor(Side.RIGHT)?.permanentHeaderComponent = EditorNotificationPanel(EditorNotificationPanel.Status.Info).apply {
                text = "Your local file has uncommitted changes, so this shows the PR's version from Azure DevOps, without inspections."
            }
        }

        val change = request.getUserData(PrDiffKeys.CHANGE_KEY) ?: return
        val inSync: (Side) -> Boolean = { side -> side != Side.RIGHT || localSync?.inSync != false }
        val target = CommentTarget(project, pr, change, layout, lifetime, inSync)
        for (editor in layout.editors) AddCommentGutter.install(editor, target, lifetime)
    }
}

private class ThreadInlays(
    private val project: Project,
    private val pr: PrDiffContext,
    private val path: String,
    /** Disposed with the diff viewer. */
    private val lifetime: Disposable,
    private val layout: DiffLayout,
) {
    private val prId = pr.details.pullRequest.pullRequestId
    private val expanded = mutableMapOf<Int, Boolean>()
    private val drafts = mutableMapOf<Int, String>()
    private var inlays: Disposable? = null

    /** Bumped after each rediff, for layouts that rebuild their editor content then (unified). */
    private val rediffs = MutableStateFlow(0)

    fun start() {
        layout.onRediff { rediffs.value++ }
        val service = PrReviewService.getInstance(project)
        val job = service.scope.launch(Dispatchers.EDT) {
            if (service.threads(prId).value == null) {
                launch {
                    try {
                        service.reloadThreads(pr.repo.remote, prId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The details panel reports load errors; inlays just stay empty.
                    }
                }
            }
            // Re-render on new threads, after a rediff, and when "Show" asks for one of this file's threads.
            combine(service.threads(prId), service.reveals, rediffs) { threads, reveal, rediff ->
                val mine = threads.orEmpty().filter { it.filePath == path }
                Triple(mine, reveal?.takeIf { (pr, thread) -> pr == prId && mine.any { it.id == thread } }, rediff)
            }.distinctUntilChanged().collect { (threads, _, _) -> render(threads) }
        }
        Disposer.register(lifetime) { job.cancel() }
    }

    private fun render(threads: List<CommentThread>) {
        inlays?.let(Disposer::dispose)
        inlays = null
        if (!layout.isReady) return
        val parent = Disposer.newDisposable(lifetime, "ADO PR threads")
        inlays = parent
        val reveal = PrReviewService.getInstance(project).takeReveal(prId, threads.map { it.id })
        reveal?.let { expanded[it] = true }
        for (thread in threads) {
            val context = thread.threadContext ?: continue
            val (side, position) = when {
                context.rightFileStart != null -> Side.RIGHT to (context.rightFileEnd ?: context.rightFileStart)
                context.leftFileStart != null -> Side.LEFT to (context.leftFileEnd ?: context.leftFileStart)
                else -> continue
            }
            val (editor, line) = layout.toEditor(side, position.line - 1) ?: continue
            val panel = ThreadPanel(
                project, pr.repo.remote, prId, thread,
                expanded = expanded[thread.id] ?: !thread.threadStatus.isResolved,
                onExpandedChange = { expanded[thread.id] = it },
                draft = drafts[thread.id].orEmpty(),
                onDraftChange = { drafts[thread.id] = it },
            )
            ThreadInlay.add(editor, line + 1, panel, parent)
            if (thread.id == reveal) {
                editor.caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
                editor.scrollingModel.scrollTo(LogicalPosition(line, 0), ScrollType.CENTER)
            }
        }
    }
}
