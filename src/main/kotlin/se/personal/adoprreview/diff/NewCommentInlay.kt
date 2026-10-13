package se.personal.adoprreview.diff

import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import se.personal.adoprreview.AdoNotifications
import se.personal.adoprreview.api.dto.ChangeEntry
import se.personal.adoprreview.api.dto.FilePosition
import se.personal.adoprreview.api.dto.NewComment
import se.personal.adoprreview.api.dto.NewThread
import se.personal.adoprreview.api.dto.PrThreadContext
import se.personal.adoprreview.api.dto.ThreadContext
import se.personal.adoprreview.api.dto.ThreadStatus
import se.personal.adoprreview.state.PrReviewService
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Which PR file a diff's editors show, and how their lines map to PR sides ([layout]); stored on the diff editors by
 * [PrDiffExtension]. [viewer] bounds inlay lifetimes.
 */
class CommentTarget(
    val project: Project,
    val pr: PrDiffContext,
    val change: ChangeEntry,
    val layout: DiffLayout,
    val viewer: Disposable,
    /** False for a side whose text no longer matches the PR (local file edited), so line numbers would be wrong. */
    val inSync: (Side) -> Boolean = { true },
) {
    /**
     * Converts a range of editor lines to a side and that side's lines. If the range spans both sides of a unified
     * diff, it is narrowed to its last line.
     */
    fun toPr(editor: Editor, range: CommentRange): Pair<Side, CommentRange>? {
        val editorEx = editor as? EditorEx ?: return null
        val (side, endLine) = layout.fromEditor(editorEx, range.endLine) ?: return null
        val start = layout.fromEditor(editorEx, range.startLine)?.takeIf { it.first == side && it.second <= endLine }
        return side to if (start != null) range.copy(startLine = start.second, endLine = endLine)
        else range.copy(startLine = endLine, startColumn = 0, endLine = endLine)
    }

    companion object {
        val KEY: Key<CommentTarget> = Key.create("AdoPrReview.commentTarget")

        fun of(editor: Editor?): CommentTarget? = editor?.getUserData(KEY)
    }
}

/** A 0-based, inclusive line range with 0-based columns, as selected in the editor. */
data class CommentRange(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int) {
    companion object {
        /** The whole of [line]. */
        fun line(editor: Editor, line: Int): CommentRange {
            val document = editor.document
            return CommentRange(line, 0, line, document.getLineEndOffset(line) - document.getLineStartOffset(line))
        }

        /** The editor's selection, or null if nothing is selected. A selection ending at column 0 excludes that line. */
        fun selection(editor: Editor): CommentRange? {
            val selection = editor.selectionModel
            if (!selection.hasSelection()) return null
            val document = editor.document
            val startLine = document.getLineNumber(selection.selectionStart)
            var endLine = document.getLineNumber(selection.selectionEnd)
            var endColumn = selection.selectionEnd - document.getLineStartOffset(endLine)
            if (endColumn == 0 && endLine > startLine) {
                endLine--
                endColumn = document.getLineEndOffset(endLine) - document.getLineStartOffset(endLine)
            }
            return CommentRange(startLine, selection.selectionStart - document.getLineStartOffset(startLine), endLine, endColumn)
        }
    }
}

object NewCommentInlay {

    /** Open new-comment inlays per editor, keyed by end line, so a second click focuses the existing one. */
    private val OPEN: Key<MutableMap<Int, NewCommentPanel>> = Key.create("AdoPrReview.newComments")

    /** Opens a new-comment box under the last line of [editorRange] (editor lines), if those lines map to the PR. */
    fun open(editor: EditorEx, target: CommentTarget, editorRange: CommentRange) {
        val (side, range) = target.toPr(editor, editorRange) ?: return
        if (!target.inSync(side)) return
        val anchor = editorRange.endLine
        val open = editor.getUserData(OPEN) ?: mutableMapOf<Int, NewCommentPanel>().also { editor.putUserData(OPEN, it) }
        open[anchor]?.let {
            it.focus()
            return
        }
        val disposable = Disposer.newDisposable("ADO PR new comment")
        Disposer.register(target.viewer, disposable)
        // Closing removes the inlay; Swing button listeners don't hold the write-intent lock that needs.
        val panel = NewCommentPanel(target, side, range) { WriteIntentReadAction.run { Disposer.dispose(disposable) } }
        open[anchor] = panel
        Disposer.register(disposable) { open.remove(anchor) }
        ThreadInlay.add(editor, anchor + 1, panel, disposable)
        panel.focus()
    }
}

class NewCommentPanel(
    private val target: CommentTarget,
    private val side: Side,
    /** In the PR's lines of [side]. */
    private val range: CommentRange,
    private val close: () -> Unit,
) : InlayCard(VerticalLayout(JBUI.scale(6)), InlayCard.NEW) {

    private val field = EditorTextField("", target.project, FileTypes.PLAIN_TEXT).apply {
        setOneLineMode(false)
        setPlaceholder("Comment…  (${KeymapUtil.getKeystrokeText(SUBMIT_KEY)} to post, Esc to cancel)")
        setShowPlaceholderWhenFocused(true)
        border = JBUI.Borders.compound(RoundedLineBorder(JBColor.border(), JBUI.scale(6)), JBUI.Borders.empty(3, 6))
        addSettingsProvider { editor ->
            editor.settings.isUseSoftWraps = true
            DumbAwareAction.create { submit() }.registerCustomShortcutSet(CustomShortcutSet(SUBMIT_KEY), editor.contentComponent)
            DumbAwareAction.create { cancel() }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)), editor.contentComponent)
        }
    }
    private val submitButton = JButton("Comment").apply { isEnabled = false }
    private val cancelButton = JButton("Cancel")

    init {

        val lines = if (range.startLine == range.endLine) "line ${range.startLine + 1}" else "lines ${range.startLine + 1}–${range.endLine + 1}"
        add(SimpleColoredComponent().apply {
            ipad = JBUI.emptyInsets()
            append("New comment", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("  ·  $lines, ${if (side == Side.RIGHT) "source" else "target"} side", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        })
        add(field)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(submitButton)
            add(cancelButton)
        })

        field.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                submitButton.isEnabled = field.text.isNotBlank()
            }
        })
        submitButton.addActionListener { submit() }
        cancelButton.addActionListener { cancel() }
    }

    fun focus() {
        IdeFocusManager.getInstance(target.project).requestFocus(field, true)
    }

    private fun cancel() {
        if (field.isEnabled) close()
    }

    private fun submit() {
        val text = field.text.trim()
        if (text.isEmpty() || !field.isEnabled) return
        if (!target.inSync(side)) {
            AdoNotifications.error(
                target.project, "Comment not posted",
                IllegalStateException("The local file no longer matches the PR, so the line would be wrong. Undo your edits or reopen the diff."),
            )
            return
        }
        val details = target.pr.details
        val prId = details.pullRequest.pullRequestId
        val start = FilePosition(range.startLine + 1, range.startColumn + 1)
        val end = FilePosition(range.endLine + 1, range.endColumn + 1)
        val thread = NewThread(
            comments = listOf(NewComment(text, parentCommentId = 0, commentType = 1)),
            status = ThreadStatus.ACTIVE.value,
            threadContext = if (side == Side.RIGHT) {
                ThreadContext(filePath = target.change.path, rightFileStart = start, rightFileEnd = end)
            } else {
                // filePath is always the PR's (new) path; the side is given by left vs right positions.
                ThreadContext(filePath = target.change.path, leftFileStart = start, leftFileEnd = end)
            },
            pullRequestThreadContext = PrThreadContext(
                changeTrackingId = target.change.changeTrackingId,
                iterationContext = details.iterationContext,
            ),
        )

        setBusy(true)
        val service = PrReviewService.getInstance(target.project)
        service.scope.launch(Dispatchers.EDT) {
            try {
                // On success the reloaded threads show the new thread, so this inlay goes away.
                service.createThread(target.pr.repo.remote, prId, thread)
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CopyPasteManager.getInstance().setContents(StringSelection(text))
                AdoNotifications.error(target.project, "Couldn't post comment (text copied to clipboard)", e)
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        field.isEnabled = !busy
        submitButton.isEnabled = !busy && field.text.isNotBlank()
        cancelButton.isEnabled = !busy
    }

    private companion object {
        val SUBMIT_KEY: KeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
    }
}
