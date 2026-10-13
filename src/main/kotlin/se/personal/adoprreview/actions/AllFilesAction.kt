package se.personal.adoprreview.actions

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import se.personal.adoprreview.diff.PrDiffKeys
import se.personal.adoprreview.diff.PrDiffOpener

/** "All Files" on the toolbar of a PR file diff: opens the PR's scrolling all-files view. */
class AllFilesAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(DiffDataKeys.DIFF_REQUEST)?.getUserData(PrDiffKeys.PR_KEY) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val context = e.getData(DiffDataKeys.DIFF_REQUEST)?.getUserData(PrDiffKeys.PR_KEY) ?: return
        PrDiffOpener.openAll(project, context, context.orderedFiles)
    }
}
