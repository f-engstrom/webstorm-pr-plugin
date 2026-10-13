package se.personal.adoprreview.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.editor.DiffEditorViewerFileEditor
import com.intellij.diff.impl.CacheDiffRequestChainProcessor
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.combined.CombinedBlockProducer
import com.intellij.diff.tools.combined.CombinedDiffVirtualFileImpl
import com.intellij.diff.tools.util.base.TextDiffSettingsHolder
import com.intellij.diff.util.DiffPlaces
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.tools.combined.CombinedPathBlockId
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.FileStatus
import com.intellij.util.PathUtil
import com.intellij.vcsUtil.VcsUtil
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import se.personal.adoprreview.api.AdoClient
import se.personal.adoprreview.api.AdoException
import se.personal.adoprreview.api.dto.ChangeEntry
import se.personal.adoprreview.api.dto.ChangeKind
import se.personal.adoprreview.auth.AzCliException
import se.personal.adoprreview.repo.ResolvedRepo
import se.personal.adoprreview.state.AdoSettings
import se.personal.adoprreview.state.PrDetails
import java.io.File

/** Everything a diff request needs to know about the PR it belongs to. */
class PrDiffContext(val repo: ResolvedRepo, val details: PrDetails) {
    /** Changed files in the order the Files tree shows them (also the diff order); set by the details panel. */
    var orderedFiles: List<ChangeEntry> = details.changes
}

object PrDiffKeys {
    val PR_KEY: Key<PrDiffContext> = Key.create("AdoPrReview.pr")
    val PATH_KEY: Key<String> = Key.create("AdoPrReview.path")
    val CHANGE_KEY: Key<ChangeEntry> = Key.create("AdoPrReview.change")

    /** Set when the right side is the real project file; holds the PR's text of it, for [LocalFileSync]. */
    val LOCAL_PR_TEXT_KEY: Key<String> = Key.create("AdoPrReview.localPrText")

    /** Set when the PR is checked out but this file has local changes, so the downloaded copy is shown instead. */
    val LOCAL_CHANGES_KEY: Key<Boolean> = Key.create("AdoPrReview.localChanges")
}

object PrDiffOpener {

    /** Identifies the diff tab of one PR state, so reopening reuses the tab until the PR gets new commits. */
    private val TAB_KEY: Key<String> = Key.create("AdoPrReview.diffTab")

    /** Opens all [changes] as one diff chain (F7 / Shift+F7 to step), starting at [selected]. One tab per PR. */
    fun open(project: Project, context: PrDiffContext, changes: List<ChangeEntry>, selected: Int) {
        if (changes.isEmpty()) return
        // Called from Swing listeners, which since 2026.x don't hold the write-intent lock. Diff editors for real
        // project files read the model while being created (e.g. the line status tracker), so take the lock here.
        WriteIntentReadAction.run { openInTab(project, context, changes, selected) }
    }

    /**
     * Opens all [changes] in one scrolling "all files" view (the platform's combined diff), one block per file,
     * loaded lazily as you scroll. One tab per PR state, like [open].
     */
    fun openAll(project: Project, context: PrDiffContext, changes: List<ChangeEntry>) {
        if (changes.isEmpty()) return
        WriteIntentReadAction.run {
            val details = context.details
            val prId = details.pullRequest.pullRequestId
            val head = context.repo.gitRepository.currentRevision
            val tabKey = "$prId:${details.baseCommit}:${details.sourceCommit}:$head:${changes.joinToString(",") { it.path }.hashCode()}"
            val editorManager = FileEditorManager.getInstance(project)
            val openTabs = editorManager.openFiles.filterIsInstance<CombinedDiffVirtualFileImpl>()
                .filter { it.getUserData(ALL_FILES_KEY)?.substringBefore(':') == "$prId" }
            openTabs.firstOrNull { it.getUserData(ALL_FILES_KEY) == tabKey }?.let {
                editorManager.openFile(it, true)
                return@run
            }
            openTabs.forEach(editorManager::closeFile)
            val blocks = changes.map { change ->
                val path = VcsUtil.getFilePath(File(context.repo.gitRepository.root.path, change.path), false)
                CombinedBlockProducer(CombinedPathBlockId(path, fileStatus(change), prId), FileDiffProducer(project, context, change, forOverview = true))
            }
            val title = "PR #$prId: all files"
            // Exactly like the platform's own "All in One Diff" (its file class, opened through
            // DiffEditorTabFilesManager). A custom subclass or a plain FileEditorManager.openFile left the view
            // stuck on "Loading…" without ever requesting block content.
            val file = CombinedDiffVirtualFileImpl(project, blocks, title, title)
            file.putUserData(ALL_FILES_KEY, tabKey)
            val editors = DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, true)
            editors.filterIsInstance<DiffEditorViewerFileEditor>().forEach { collapseUnchanged(it.editorViewer.context) }
        }
    }

    /** Marks the all-files tab of a PR state, like [TAB_KEY] for single-file diffs. */
    private val ALL_FILES_KEY: Key<String> = Key.create("AdoPrReview.allFilesTab")

    /** Settings for the all-files view when the combined diff would otherwise share the IDE's default diff settings. */
    private const val ALL_FILES_PLACE = "AdoPrReview.AllFiles"

    /**
     * Makes the all-files view collapse unchanged code; whole files make blocks so tall that macOS can't allocate
     * their drawing surface ("can't create offscreen surface", reported by the IDE as low memory).
     *
     * The view caches its settings in its context when created. If they belong to a combined-diff place of their own,
     * collapse that once (the view's own toggle keeps working and later changes stick). If they are the IDE-wide
     * default, leave the user's default alone and give the view this plugin's settings instead.
     */
    private fun collapseUnchanged(context: DiffContext) {
        val key = TextDiffSettingsHolder.TextDiffSettings.KEY
        val current = context.getUserData(key)
        val place = current?.place ?: context.getUserData(DiffUserDataKeys.PLACE)
        val shared = current == null || place == null || place == DiffPlaces.DEFAULT || place == DiffPlaces.CHANGES_VIEW
        val settings = AdoSettings.getInstance()
        if (shared) {
            val own = TextDiffSettingsHolder.TextDiffSettings.getSettings(ALL_FILES_PLACE)
            if (!settings.allFilesSettingsInitialized) {
                own.collapse()
                settings.allFilesSettingsInitialized = true
            }
            context.putUserData(key, own)
        } else if (!settings.allFilesCollapseApplied) {
            current.collapse()
            settings.allFilesCollapseApplied = true
        }
    }

    private fun TextDiffSettingsHolder.TextDiffSettings.collapse() {
        isExpandByDefault = false
        contextRange = 4
    }

    private fun fileStatus(change: ChangeEntry): FileStatus = when (change.kind) {
        ChangeKind.ADD -> FileStatus.ADDED
        ChangeKind.DELETE -> FileStatus.DELETED
        ChangeKind.EDIT, ChangeKind.RENAME -> FileStatus.MODIFIED
    }

    private fun openInTab(project: Project, context: PrDiffContext, changes: List<ChangeEntry>, selected: Int) {
        val details = context.details
        val prId = details.pullRequest.pullRequestId
        // The local HEAD is part of the key: after a checkout the tab is rebuilt, so it can switch to the local files.
        val head = context.repo.gitRepository.currentRevision
        val tabKey = "$prId:${details.baseCommit}:${details.sourceCommit}:$head:${changes.joinToString(",") { it.path }.hashCode()}"
        val editorManager = FileEditorManager.getInstance(project)
        val openTabs = editorManager.openFiles.filterIsInstance<ChainDiffVirtualFile>()
            .filter { it.getUserData(TAB_KEY)?.substringBefore(':') == "$prId" }

        openTabs.firstOrNull { it.getUserData(TAB_KEY) == tabKey }?.let { existing ->
            val processor = editorManager.openFile(existing, true).filterIsInstance<DiffEditorViewerFileEditor>()
                .firstNotNullOfOrNull { it.editorViewer as? CacheDiffRequestChainProcessor }
            if (processor != null) {
                processor.setCurrentRequest(selected)
                return
            }
        }
        // The PR changed since that tab was opened (or it can't be reused): replace it.
        openTabs.forEach(editorManager::closeFile)

        val chain = SimpleDiffRequestChain.fromProducers(changes.map { FileDiffProducer(project, context, it) }, selected)
        val file = ChainDiffVirtualFile(chain, "PR #$prId")
        file.putUserData(TAB_KEY, tabKey)
        editorManager.openFile(file, true)
    }
}

/** Above this many changed lines, the all-files view shows a placeholder instead of the diff. */
private const val OVERVIEW_MAX_CHANGED_LINES = 600

/** Loads one file's two sides when the diff viewer first shows it. */
internal class FileDiffProducer(
    private val project: Project,
    private val context: PrDiffContext,
    private val change: ChangeEntry,
    /** In the all-files view, very large changes become a placeholder (see [OVERVIEW_MAX_CHANGED_LINES]). */
    private val forOverview: Boolean = false,
) : DiffRequestProducer {

    override fun getName(): String = change.path.removePrefix("/")

    override fun getContentType(): FileType =
        FileTypeManager.getInstance().getFileTypeByFileName(PathUtil.getFileName(change.path))

    override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
        val details = this.context.details
        val remote = this.context.repo.remote
        val baseCommit = details.baseCommit ?: throw DiffRequestProducerException("The PR has no base commit yet.")
        val sourceCommit = details.sourceCommit ?: throw DiffRequestProducerException("The PR has no source commit yet.")

        val (base, source) = try {
            runBlockingCancellable {
                coroutineScope {
                    val client = AdoClient.getInstance()
                    val base = async { if (change.kind == ChangeKind.ADD) null else client.fileContent(remote, change.basePath, baseCommit) }
                    val source = async { if (change.kind == ChangeKind.DELETE) null else client.fileContent(remote, change.path, sourceCommit) }
                    base.await() to source.await()
                }
            }
        } catch (e: AdoException) {
            throw DiffRequestProducerException(e.message, e)
        } catch (e: AzCliException) {
            throw DiffRequestProducerException(e.message, e)
        }

        val pr = details.pullRequest
        if (forOverview) tooLargeForOverview(base, source, indicator)?.let { return it }
        val local = source?.let { localContent(it) }
        val request = SimpleDiffRequest(
            "#${pr.pullRequestId} ${getName()}",
            content(base, change.basePath),
            (local as? LocalFile.Used)?.content ?: content(source, change.path),
            if (details.baseIteration == 0) "${pr.targetBranch} (merge base ${baseCommit.take(8)})"
            else "Update ${details.baseIteration} (${baseCommit.take(8)})",
            when {
                local is LocalFile.Used -> "${pr.sourceBranch} (${sourceCommit.take(8)}, local file)"
                details.isFullRange -> "${pr.sourceBranch} (${sourceCommit.take(8)})"
                else -> "Update ${details.headIteration} (${sourceCommit.take(8)})"
            },
        )
        when (local) {
            is LocalFile.Used -> request.putUserData(PrDiffKeys.LOCAL_PR_TEXT_KEY, local.prText)
            LocalFile.HasLocalChanges -> request.putUserData(PrDiffKeys.LOCAL_CHANGES_KEY, true)
            LocalFile.NotApplicable, null -> {}
        }
        request.putUserData(PrDiffKeys.PR_KEY, this.context)
        request.putUserData(PrDiffKeys.PATH_KEY, change.path)
        request.putUserData(PrDiffKeys.CHANGE_KEY, change)
        return request
    }

    /**
     * A placeholder when the change is too big for the all-files view. Changed lines can't be collapsed, and a block
     * thousands of lines tall exceeds what macOS can allocate to draw it ("can't create offscreen surface").
     */
    private fun tooLargeForOverview(base: ByteArray?, source: ByteArray?, indicator: ProgressIndicator): DiffRequest? {
        if (listOfNotNull(base, source).any { bytes -> bytes.take(8000).any { it == 0.toByte() } }) return null // binary
        val baseText = base?.toString(Charsets.UTF_8)
        val sourceText = source?.toString(Charsets.UTF_8)
        val changed = if (baseText == null || sourceText == null) {
            (baseText ?: sourceText).orEmpty().lineSequence().count()
        } else {
            ComparisonManager.getInstance().compareLines(baseText, sourceText, ComparisonPolicy.DEFAULT, indicator)
                .sumOf { (it.endLine1 - it.startLine1) + (it.endLine2 - it.startLine2) }
        }
        if (changed <= OVERVIEW_MAX_CHANGED_LINES) return null
        return MessageDiffRequest(
            getName(),
            "$changed changed lines: too large for the all-files view. Open this file from the Files tab to see the diff.",
        )
    }

    private sealed interface LocalFile {
        /** The project's file is shown; [prText] is the PR's version, for [LocalFileSync]. */
        class Used(val content: DocumentContent, val prText: String) : LocalFile

        /** The PR is checked out, but this file differs from it (uncommitted or unsaved changes). */
        data object HasLocalChanges : LocalFile

        data object NotApplicable : LocalFile
    }

    /**
     * The project's own file for the right side, so inspections, type checking and navigation work, but only
     * if its current text (including unsaved edits) is exactly the PR's version. Otherwise the downloaded copy is used.
     */
    private fun localContent(prBytes: ByteArray): LocalFile {
        val details = context.details
        if (!details.isFullRange) return LocalFile.NotApplicable
        val file = context.repo.gitRepository.root.findFileByRelativePath(change.path.trimStart('/')) ?: return LocalFile.NotApplicable
        if (file.isDirectory || file.fileType.isBinary) return LocalFile.NotApplicable
        val prText = StringUtil.convertLineSeparators(LoadTextUtil.getTextByBinaryPresentation(prBytes, file).toString())
        val document = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(file) } ?: return LocalFile.NotApplicable
        if (!StringUtil.equals(document.immutableCharSequence, prText)) {
            val checkedOut = context.repo.gitRepository.currentRevision.equals(details.sourceCommit, ignoreCase = true)
            return if (checkedOut) LocalFile.HasLocalChanges else LocalFile.NotApplicable
        }
        val content = DiffContentFactory.getInstance().create(project, file) as? DocumentContent ?: return LocalFile.NotApplicable
        return LocalFile.Used(content, prText)
    }

    private fun content(bytes: ByteArray?, path: String): DiffContent {
        val factory = DiffContentFactory.getInstance()
        if (bytes == null) return factory.createEmpty()
        // A path inside the local checkout gives the right file type, highlighting and charset detection.
        val filePath = VcsUtil.getFilePath(File(context.repo.gitRepository.root.path, path), false)
        return factory.createFromBytes(project, bytes, filePath)
    }
}
