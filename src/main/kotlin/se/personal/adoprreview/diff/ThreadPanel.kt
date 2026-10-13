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
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
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
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * One review thread: comments rendered as Markdown, plus a reply box and Resolve / Reactivate.
 * Used both as a diff inlay and for general (file-less) threads in the details panel.
 * Successful actions reload the PR's threads, which rebuilds this component.
 */
class ThreadPanel(
    private val project: Project,
    private val remote: AdoRemote,
    private val prId: Int,
    private val thread: CommentThread,
    expanded: Boolean,
    private val onExpandedChange: (Boolean) -> Unit,
    /** Unsent reply text, kept by the owner so it survives rebuilds when threads reload in the background. */
    draft: String = "",
    private val onDraftChange: (String) -> Unit = {},
) : JPanel(BorderLayout()) {

    private val header = SimpleColoredComponent().apply {
        ipad = JBUI.emptyInsets()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val body = JPanel(VerticalLayout(JBUI.scale(8))).apply { isOpaque = false }
    private val replyField = EditorTextField(draft, project, FileTypes.PLAIN_TEXT).apply {
        setOneLineMode(false)
        setPlaceholder("Reply…  (${KeymapUtil.getKeystrokeText(SUBMIT_KEY)} to send)")
        setShowPlaceholderWhenFocused(true)
        border = JBUI.Borders.compound(RoundedLineBorder(JBColor.border(), JBUI.scale(6)), JBUI.Borders.empty(3, 6))
        addSettingsProvider { editor ->
            editor.settings.isUseSoftWraps = true
            DumbAwareAction.create { submitReply() }.registerCustomShortcutSet(CustomShortcutSet(SUBMIT_KEY), editor.contentComponent)
        }
    }
    private val replyButton = JButton("Reply").apply { isEnabled = draft.isNotBlank() }
    private val statusButton = JButton(if (thread.threadStatus.isResolved) "Reactivate" else "Resolve")

    private var expanded = expanded
        set(value) {
            field = value
            body.isVisible = value
            renderHeader()
            onExpandedChange(value)
            revalidate()
        }

    init {
        isOpaque = true
        background = JBColor.namedColor("EditorTabs.background", UIUtil.getPanelBackground())
        border = JBUI.Borders.compound(RoundedLineBorder(JBColor.border(), JBUI.scale(8)), JBUI.Borders.empty(6, 10))

        thread.visibleComments.forEach { body.add(commentComponent(it)) }
        body.add(replyField)
        body.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(replyButton)
            add(statusButton)
        })

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
                onDraftChange(replyField.text)
            }
        })
        replyButton.addActionListener { submitReply() }
        statusButton.addActionListener {
            val status = if (thread.threadStatus.isResolved) ThreadStatus.ACTIVE else ThreadStatus.FIXED
            run("Couldn't update thread") { PrReviewService.getInstance(project).setThreadStatus(remote, prId, thread, status) }
        }
    }

    private fun submitReply() {
        val text = replyField.text.trim()
        if (text.isEmpty() || !replyField.isEnabled) return
        // Posting reloads the threads, which rebuilds this panel from the draft: clear it first, restore on failure.
        val draft = replyField.text
        onDraftChange("")
        run("Couldn't post reply", onFailure = { onDraftChange(draft) }) {
            PrReviewService.getInstance(project).reply(remote, prId, thread, text)
        }
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
        add(SimpleColoredComponent().apply {
            ipad = JBUI.emptyInsets()
            append(comment.author.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            comment.publishedDate?.let { date ->
                runCatching { DateFormatUtil.formatPrettyDateTime(Instant.parse(date).toEpochMilli()) }.getOrNull()
                    ?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
        })
        add(Markdown.createPane().apply { text = Markdown.toHtml(comment.content.orEmpty()) })
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
        val ACTIVE = JBColor(0xB26A00, 0xE0A040)
        val RESOLVED = JBColor(0x2E7D32, 0x6CBF6C)
    }
}
