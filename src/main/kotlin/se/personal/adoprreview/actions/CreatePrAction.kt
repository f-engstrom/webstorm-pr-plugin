package se.personal.adoprreview.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import se.personal.adoprreview.AdoNotifications
import se.personal.adoprreview.state.PrReviewService
import se.personal.adoprreview.toolwindow.PrListPanel
import se.personal.adoprreview.workflow.CreateOutcome
import se.personal.adoprreview.workflow.ideWorkflow
import se.personal.adoprreview.workflow.savedJiraTracker

/** "+" in the list toolbar: creates a PR from the current branch, pushing it first if needed. */
class CreatePrAction(private val panel: PrListPanel) :
    DumbAwareAction("Create Pull Request…", "Create a pull request from the current branch", AllIcons.General.Add) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel.repo?.gitRepository?.currentBranch != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = panel.repo ?: return
        val branch = repo.gitRepository.currentBranch?.name ?: return
        val remote = repo.remote
        PrReviewService.getInstance(project).launchOperation("Couldn't create pull request") {
            when (val outcome = ideWorkflow(project, repo, "Create Pull Request").createPr(branch, savedJiraTracker(remote.org))) {
                is CreateOutcome.AlreadyOpen -> {
                    AdoNotifications.info(project, "Pull request already open", "#${outcome.prId} is open from $branch.", remote.webUrl(outcome.prId))
                    panel.refreshAndSelect(outcome.prId)
                }
                is CreateOutcome.Created -> {
                    val stale = if (outcome.fromStaleRemote) "<br>It shows $branch as it is on ${repo.gitRemote.name}, not your newer local commits; push to update it." else ""
                    AdoNotifications.info(project, "Pull request created", "#${outcome.prId} ${outcome.title}$stale", remote.webUrl(outcome.prId))
                    panel.refreshAndSelect(outcome.prId)
                }
                CreateOutcome.Cancelled -> Unit
            }
        }
    }
}
