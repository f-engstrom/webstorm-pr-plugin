package se.personal.adoprreview.toolwindow

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import se.personal.adoprreview.actions.NotificationsToggleAction
import se.personal.adoprreview.state.PrWatcher

class PrToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PrListPanel(project, toolWindow.disposable)
        val factory = ContentFactory.getInstance()
        toolWindow.contentManager.addContent(factory.createContent(panel, "Pull Requests", false))
        toolWindow.contentManager.addContent(factory.createContent(StatusPanel(project, toolWindow.disposable), "Status", false))
        toolWindow.contentManager.addContent(factory.createContent(HelpPanel(), "Help", false))
        toolWindow.setAdditionalGearActions(DefaultActionGroup(NotificationsToggleAction()))

        val watcher = PrWatcher.getInstance(project)
        watcher.listPanel = panel
        Disposer.register(toolWindow.disposable) { if (watcher.listPanel === panel) watcher.listPanel = null }
        panel.refresh()
    }
}
