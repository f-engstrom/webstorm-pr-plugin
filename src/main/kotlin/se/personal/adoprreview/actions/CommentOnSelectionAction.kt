package se.personal.adoprreview.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.DumbAwareAction
import se.personal.adoprreview.diff.CommentRange
import se.personal.adoprreview.diff.CommentTarget
import se.personal.adoprreview.diff.NewCommentInlay

/** "Add PR Comment" in the diff editor's context menu: comments on the selection, or the caret line. */
class CommentOnSelectionAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val target = CommentTarget.of(editor)
        e.presentation.isVisible = target != null
        e.presentation.isEnabled = target != null && editor != null &&
            target.toPr(editor, range(editor))?.let { (side, _) -> target.inSync(side) } == true
    }

    private fun range(editor: Editor) =
        CommentRange.selection(editor) ?: CommentRange.line(editor, editor.caretModel.logicalPosition.line)

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) as? EditorEx ?: return
        val target = CommentTarget.of(editor) ?: return
        NewCommentInlay.open(editor, target, range(editor))
    }
}
