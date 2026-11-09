package se.personal.adoprreview.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import se.personal.adoprreview.AdoNotifications
import se.personal.adoprreview.jira.JiraClient
import se.personal.adoprreview.jira.JiraSignIn
import se.personal.adoprreview.repo.AdoRepoResolver
import se.personal.adoprreview.repo.ResolvedRepo
import se.personal.adoprreview.state.AdoSettings
import se.personal.adoprreview.state.PrReviewService
import se.personal.adoprreview.toolwindow.StartTicketDialog
import se.personal.adoprreview.workflow.StartOutcome
import se.personal.adoprreview.workflow.StartRequest
import se.personal.adoprreview.workflow.ideWorkflow
import se.personal.adoprreview.workflow.jiraTracker

/**
 * Pick a Jira ticket, then: branch `type/KEY` from the latest remote commit of the chosen branch, add an empty commit,
 * push, open a draft PR into that branch, and assign the ticket and move it to In Progress.
 */
class StartTicketAction : DumbAwareAction(
    "Start Jira Ticket…",
    "Create a branch and a draft pull request for a Jira ticket",
    AllIcons.Vcs.Branch,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && AdoRepoResolver.getInstance(project).resolve() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = AdoRepoResolver.getInstance(project).resolve() ?: return
        PrReviewService.getInstance(project).launchOperation("Couldn't start the ticket") { start(project, repo) }
    }

    private suspend fun start(project: Project, repo: ResolvedRepo) {
        val workflow = ideWorkflow(project, repo, "Start Jira Ticket")
        if (!workflow.ensureClean()) return

        val session = JiraSignIn.session(project, repo.remote.org) ?: return
        val jira = JiraClient.getInstance()
        val (issues, targets) = withBackgroundProgress(project, "Loading your Jira tickets") {
            coroutineScope {
                val targets = async { workflow.prepareStart() }
                jira.myOpenIssues(session.site, session.login) to targets.await()
            }
        }

        val settings = AdoSettings.getInstance()
        val dialog = StartTicketDialog(
            project,
            issues = issues,
            targetBranches = targets.branches,
            defaultBranch = targets.defaultBranch,
            defaultTarget = targets.preselected,
            assign = settings.startAssignToMe,
            moveToInProgress = settings.startMoveToInProgress,
            scope = PrReviewService.getInstance(project).scope,
            lookup = { key -> jira.issue(session.site, session.login, key) },
        )
        if (!dialog.showAndGet()) return
        val issue = dialog.issue ?: return
        settings.startAssignToMe = dialog.assignToMe
        settings.startMoveToInProgress = dialog.moveToInProgress

        val request = StartRequest(issue, dialog.branchName, dialog.targetBranch, dialog.assignToMe, dialog.moveToInProgress)
        val remote = repo.remote
        val activity = PrReviewService.getInstance(project).activity
        when (val outcome = workflow.startTicket(request, jiraTracker(session))) {
            is StartOutcome.Started -> {
                AdoNotifications.info(project, "Started ${issue.key}", "On ${outcome.branch} · draft PR #${outcome.prId}", remote.webUrl(outcome.prId))
                if (outcome.jiraProblems.isNotEmpty()) AdoNotifications.warn(project, "Jira wasn't fully updated", outcome.jiraProblems.joinToString("<br>"))
                activity.select(outcome.prId)
            }
            is StartOutcome.SwitchedToExisting -> if (outcome.prId != null) {
                activity.select(outcome.prId)
                AdoNotifications.info(project, "On ${outcome.branch}", "Its pull request is #${outcome.prId}.", remote.webUrl(outcome.prId))
            } else {
                AdoNotifications.info(project, "On ${outcome.branch}", "No active pull request from it yet. Use + to create one.")
            }
            is StartOutcome.PushedWithoutPr -> AdoNotifications.error(
                project, "Pushed ${outcome.branch}, but couldn't create its pull request",
                Exception("${outcome.error.message}<br>Use + to create the pull request from ${outcome.branch}."),
            )
            is StartOutcome.Failed -> AdoNotifications.error(project, "Couldn't start ${issue.key}", Exception(outcome.message.replace("\n", "<br>")))
            StartOutcome.Cancelled -> Unit
        }
    }
}
