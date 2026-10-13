package se.personal.adoprreview.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareToggleAction
import se.personal.adoprreview.api.PrFilter
import se.personal.adoprreview.state.AdoSettings
import se.personal.adoprreview.toolwindow.PrListPanel

class FilterActionGroup(private val panel: PrListPanel) : DefaultActionGroup("Filter", true) {

    init {
        templatePresentation.icon = AllIcons.General.Filter
        PrFilter.entries.forEach { add(FilterAction(it)) }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.text = "Filter: ${AdoSettings.getInstance().listFilter.label}"
    }

    private inner class FilterAction(private val filter: PrFilter) : DumbAwareToggleAction(filter.label) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent) = AdoSettings.getInstance().listFilter == filter

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (!state || AdoSettings.getInstance().listFilter == filter) return
            AdoSettings.getInstance().listFilter = filter
            panel.refresh()
        }
    }
}
