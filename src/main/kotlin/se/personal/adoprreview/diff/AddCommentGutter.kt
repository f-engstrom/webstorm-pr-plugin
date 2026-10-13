package se.personal.adoprreview.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import javax.swing.Icon

/** Shows a "+" in the gutter of the hovered line; clicking it opens a [NewCommentInlay] for that line (or the selection). */
class AddCommentGutter private constructor(private val editor: EditorEx, private val target: CommentTarget) {

    private var highlighter: RangeHighlighter? = null
    private var line = -1

    private fun install(parent: Disposable) {
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                val area = e.area
                if (area != EditorMouseEventArea.EDITING_AREA && area != EditorMouseEventArea.LINE_MARKERS_AREA &&
                    area != EditorMouseEventArea.LINE_NUMBERS_AREA && area != EditorMouseEventArea.ANNOTATIONS_AREA
                ) return
                // Only on lines that map to a PR side that may be commented on; never over inlays (thread panels).
                val line = e.logicalPosition.line
                val mapped = if (e.inlay == null && line < editor.document.lineCount) target.layout.fromEditor(editor, line) else null
                if (mapped == null || !target.inSync(mapped.first)) return hide()
                show(line)
            }
        }, parent)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseExited(e: EditorMouseEvent) = hide()
        }, parent)
        Disposer.register(parent) { hide() }
    }

    private fun show(newLine: Int) {
        if (newLine == line && highlighter?.isValid == true) return
        hide()
        line = newLine
        highlighter = editor.markupModel.addLineHighlighter(newLine, HighlighterLayer.LAST, null).apply {
            gutterIconRenderer = AddRenderer(newLine)
        }
    }

    private fun hide() {
        highlighter?.let { editor.markupModel.removeHighlighter(it) }
        highlighter = null
        line = -1
    }

    private inner class AddRenderer(private val forLine: Int) : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.General.Add
        override fun getTooltipText(): String = "Add PR comment (uses the selection if it includes this line)"
        override fun isNavigateAction(): Boolean = true
        override fun getAlignment(): Alignment = Alignment.RIGHT

        override fun getClickAction(): AnAction = DumbAwareAction.create { _: AnActionEvent ->
            val selection = CommentRange.selection(editor)?.takeIf { forLine in it.startLine..it.endLine }
            NewCommentInlay.open(editor, target, selection ?: CommentRange.line(editor, forLine))
        }

        override fun equals(other: Any?): Boolean = other is AddRenderer && other.forLine == forLine
        override fun hashCode(): Int = forLine
    }

    companion object {
        fun install(editor: EditorEx, target: CommentTarget, parent: Disposable) {
            // Nothing to comment on in the empty side of an added or deleted file.
            if (editor.document.textLength == 0 && target.layout.isReady) return
            editor.putUserData(CommentTarget.KEY, target)
            AddCommentGutter(editor, target).install(parent)
        }
    }
}
