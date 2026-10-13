package se.personal.adoprreview.diff

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.EditorNotificationPanel

/**
 * Watches the right side of a PR diff when it shows the real project file (see [PrDiffOpener]).
 * While the file's text ([document]) equals the PR's version, everything behaves as usual. Once it differs (local
 * edits, branch switch, pull), commenting on that side is disabled, because line numbers would no longer match the PR.
 * Banners go on [bannerEditor] (the right editor, or the single editor of a unified diff).
 */
class LocalFileSync(
    private val document: Document,
    private val bannerEditor: EditorEx,
    private val prText: String,
    private val prId: Int,
    private val dirtyWorkingTree: Boolean,
) {
    var inSync: Boolean = true
        private set
    private var initialized = false

    fun install(parent: Disposable) {
        update()
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = update()
        }, parent)
    }

    private fun update() {
        val nowInSync = StringUtil.equals(document.immutableCharSequence, prText)
        if (initialized && nowInSync == inSync) return
        initialized = true
        inSync = nowInSync
        bannerEditor.permanentHeaderComponent = when {
            !inSync -> banner(
                EditorNotificationPanel.Status.Warning,
                "Local file differs from PR #$prId. Commenting on the new side is off until it matches again.",
            )
            dirtyWorkingTree -> banner(
                EditorNotificationPanel.Status.Info,
                "Showing your local file. Your working tree has uncommitted changes, so inspections may reflect them.",
            )
            else -> null
        }
    }

    private fun banner(status: EditorNotificationPanel.Status, message: String) =
        EditorNotificationPanel(status).apply { text = message }
}
