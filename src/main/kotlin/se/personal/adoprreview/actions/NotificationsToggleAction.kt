package se.personal.adoprreview.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareToggleAction
import se.personal.adoprreview.state.AdoSettings

/** In the tool window's ⋮ menu: turns background PR activity notifications on or off. */
class NotificationsToggleAction : DumbAwareToggleAction(
    "Notify About PR Activity",
    "Every 5 minutes, check for new comments, votes and review requests, and show a notification",
    null,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent) = AdoSettings.getInstance().backgroundNotifications

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        AdoSettings.getInstance().backgroundNotifications = state
    }
}
