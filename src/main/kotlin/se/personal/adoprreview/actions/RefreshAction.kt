package se.personal.adoprreview.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import se.personal.adoprreview.toolwindow.PrListPanel

class RefreshAction(private val panel: PrListPanel) : DumbAwareAction("Refresh", null, AllIcons.Actions.Refresh) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) = panel.refresh(reloadUser = true)
}
