package se.personal.adoprreview.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import se.personal.adoprreview.toolwindow.PrListPanel

class OpenInBrowserAction(private val panel: PrListPanel) :
    DumbAwareAction("Open in Browser", null, AllIcons.General.Web) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel.selectedPullRequest != null && panel.repo != null
    }

    override fun actionPerformed(e: AnActionEvent) = panel.openSelectedInBrowser()
}
