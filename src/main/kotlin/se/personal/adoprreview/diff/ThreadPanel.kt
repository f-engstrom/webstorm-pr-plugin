package se.personal.adoprreview.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import se.personal.adoprreview.AdoNotifications
import se.personal.adoprreview.api.dto.Comment
import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.api.dto.ThreadStatus
import se.personal.adoprreview.repo.AdoRemote
import se.personal.adoprreview.state.PrReviewService
import se.personal.adoprreview.toolwindow.Markdown
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * UI state of one thread that must survive rebuilds (threads reload after every action and in the background):
 * whether it's expanded, the unsent reply, and comments being edited (comment ID to text).
 */
class ThreadUiState(var expanded: Boolean? = null, var replyDraft: String = "", val editing: MutableMap<Int, String> = mutableMapOf())

/**
 * One review thread: comments rendered as Markdown with likes, edit and delete on your own comments, a reply box,
 * and Resolve / Reactivate. Used as a diff inlay and for general (file-less) threads in the details panel.
 * Successful actions reload the PR's threads, which rebuilds this component from [state].
 */
class ThreadPanel(
    private val project: Project,
    private val remote: AdoRemote,
    private val prId: Int,
    private val thread: CommentThread,
    /** The signed-in user, to tell own comments and own likes apart. */
    private val myId: String,
    private val state: ThreadUiState,
) : InlayCard(BorderLayout(), if (thread.threadStatus.isResolved) InlayCard.RESOLVED else InlayCard.ACTIVE) {

    private val header = SimpleColoredComponent().apply {
        ipad = JBUI.emptyInsets()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val body = JPanel(VerticalLayout(JBUI.scale(8))).apply { isOpaque = false }
    private val replyField = textField(state.replyDraft, "Reply…  (${KeymapUtil.getKeystrokeText(SUBMIT_KEY)} to send)") { submitReply() }
    private val replyButton = JButton("Reply").apply { isEnabled = state.replyDraft.isNotBlank() }
    private val statusButton = JButton(if (thread.threadStatus.isResolved) "Reactivate" else "Resolve")

    private var expanded: Boolean = state.expanded ?: !thread.threadStatus.isResolved
        set(value) {
            field = value
            state.expanded = value
            body.isVisible = value
            renderHeader()
            revalidate()
        }

    init {
        renderBody()
        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        body.isVisible = expanded
        renderHeader()

        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                this@ThreadPanel.expanded = !this@ThreadPanel.expanded
            }
        })
        replyField.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                replyButton.isEnabled = replyField.text.isNotBlank()
                state.replyDraft = replyField.text
            }
        })
        replyButton.addActionListener { submitReply() }
        statusButton.addActionListener {
            val status = if (thread.threadStatus.isResolved) ThreadStatus.ACTIVE else ThreadStatus.FIXED
            run("Couldn't update thread") { PrReviewService.getInstance(project).setThreadStatus(remote, prId, thread, status) }
        }
    }

    /** Comments, then the reply box. Rebuilt when a comment enters or leaves edit mode. */
    private fun renderBody() {
        body.removeAll()
        thread.visibleComments.forEach { body.add(commentComponent(it)) }
        body.add(replyField)
        body.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(replyButton)
            add(statusButton)
        })
        body.revalidate()
        body.repaint()
    }

    private fun renderHeader() {
        val status = thread.threadStatus
        val comments = thread.visibleComments
        header.clear()
        header.icon = if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        header.append(status.label, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, if (status.isResolved) RESOLVED else ACTIVE))
        header.append("  ·  ${comments.size} comment${if (comments.size == 1) "" else "s"}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (!expanded) {
            comments.firstOrNull()?.let { first ->
                val preview = first.content.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(80)
                header.append("  ·  ${first.author.displayName}: ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                header.append(preview, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }
        }
    }

    private fun commentComponent(comment: Comment): JPanel = JPanel(VerticalLayout(JBUI.scale(2))).apply {
        isOpaque = false
        val own = comment.isBy(myId)
        val editing = state.editing[comment.id]

        val who = SimpleColoredComponent().apply {
            ipad = JBUI.emptyInsets()
            append(comment.author.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            comment.publishedDate?.let { date ->
                runCatching { DateFormatUtil.formatPrettyDateTime(Instant.parse(date).toEpochMilli()) }.getOrNull()
                    ?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
            if (comment.isEdited) append("  (edited)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)).apply {
            isOpaque = false
            add(likeLink(comment))
            if (own && editing == null) {
                add(ActionLink("Edit") { startEditing(comment) })
                add(ActionLink("Delete") { delete(comment) })
            }
        }
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(who, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
        })
        add(if (editing != null) editor(comment, editing) else Markdown.createPane().apply { text = Markdown.toHtml(comment.content.orEmpty()) })
    }

    /** 👍 with the count; filled when I liked it. The tooltip lists who liked it. */
    private fun likeLink(comment: Comment): JComponent {
        val liked = comment.isLikedBy(myId)
        val count = comment.usersLiked.size
        return ActionLink(if (count > 0) "$count" else "") {
            run("Couldn't update like") { PrReviewService.getInstance(project).setLiked(remote, prId, thread, comment, !liked) }
        }.apply {
            icon = if (liked) AllIcons.Ide.LikeSelected else if (count > 0) AllIcons.Ide.Like else AllIcons.Ide.LikeDimmed
            toolTipText = when {
                count == 0 -> "Like"
                else -> "Liked by ${comment.usersLiked.joinToString { it.displayName }}" + if (liked) " (click to unlike)" else ""
            }
        }
    }

    private fun startEditing(comment: Comment) {
        state.editing[comment.id] = comment.rawContent ?: comment.content.orEmpty()
        if (!expanded) expanded = true
        renderBody()
    }

    private fun stopEditing(comment: Comment) {
        state.editing.remove(comment.id)
        renderBody()
    }

    /** Edit mode for one of my comments: the original Markdown (with real mentions), Save and Cancel. */
    private fun editor(comment: Comment, text: String): JComponent {
        lateinit var save: () -> Unit
        val field = textField(text, "Edit comment…  (${KeymapUtil.getKeystrokeText(SUBMIT_KEY)} to save, Esc to cancel)", onEscape = { stopEditing(comment) }) { save() }
        field.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                state.editing[comment.id] = field.text
            }
        })
        save = {
            val newText = field.text.trim()
            if (newText.isNotEmpty()) {
                val draft = field.text
                state.editing.remove(comment.id)
                run("Couldn't save comment", onFailure = { state.editing[comment.id] = draft; renderBody() }) {
                    PrReviewService.getInstance(project).editComment(remote, prId, thread, comment, newText)
                }
            }
        }
        return JPanel(VerticalLayout(JBUI.scale(4))).apply {
            isOpaque = false
            add(field)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(JButton("Save").apply { addActionListener { save() } })
                add(JButton("Cancel").apply { addActionListener { stopEditing(comment) } })
            })
        }
    }

    private fun delete(comment: Comment) {
        val answer = Messages.showYesNoDialog(
            project, "Delete this comment? This can't be undone.", "Delete Comment", "Delete", "Cancel", Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        run("Couldn't delete comment") { PrReviewService.getInstance(project).deleteComment(remote, prId, thread, comment) }
    }

    private fun submitReply() {
        val text = replyField.text.trim()
        if (text.isEmpty() || !replyField.isEnabled) return
        // Posting reloads the threads, which rebuilds this panel from the state: clear the draft first, restore on failure.
        val draft = replyField.text
        state.replyDraft = ""
        run("Couldn't post reply", onFailure = { state.replyDraft = draft }) {
            PrReviewService.getInstance(project).reply(remote, prId, thread, text)
        }
    }

    private fun textField(text: String, placeholder: String, onEscape: (() -> Unit)? = null, onSubmit: () -> Unit) =
        EditorTextField(text, project, FileTypes.PLAIN_TEXT).apply {
            setOneLineMode(false)
            setPlaceholder(placeholder)
            setShowPlaceholderWhenFocused(true)
            border = JBUI.Borders.compound(RoundedLineBorder(JBColor.border(), JBUI.scale(6)), JBUI.Borders.empty(3, 6))
            addSettingsProvider { editor ->
                editor.settings.isUseSoftWraps = true
                DumbAwareAction.create { onSubmit() }.registerCustomShortcutSet(CustomShortcutSet(SUBMIT_KEY), editor.contentComponent)
                onEscape?.let { escape ->
                    DumbAwareAction.create { escape() }
                        .registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)), editor.contentComponent)
                }
            }
        }

    private fun run(errorTitle: String, onFailure: () -> Unit = {}, action: suspend () -> Unit) {
        setBusy(true)
        PrReviewService.getInstance(project).scope.launch(Dispatchers.EDT) {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure()
                AdoNotifications.error(project, errorTitle, e)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        replyButton.isEnabled = !busy && replyField.text.isNotBlank()
        statusButton.isEnabled = !busy
        replyField.isEnabled = !busy
    }

    private companion object {
        val SUBMIT_KEY: KeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        // Status label colours in the header.
        val ACTIVE = JBColor(0xB26A00, 0xE0A040)
        val RESOLVED = JBColor(0x2E7D32, 0x6CBF6C)
    }
}
