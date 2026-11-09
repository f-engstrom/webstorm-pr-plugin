package se.personal.adoprreview.actions

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.personal.adoprreview.AdoNotifications
import se.personal.adoprreview.api.AdoClient
import se.personal.adoprreview.api.dto.NewPullRequest
import se.personal.adoprreview.jira.JiraClient
import se.personal.adoprreview.jira.JiraIssue
import se.personal.adoprreview.jira.JiraSession
import se.personal.adoprreview.jira.JiraSignIn
import se.personal.adoprreview.jira.TicketBranches
import se.personal.adoprreview.repo.AdoRepoResolver
import se.personal.adoprreview.repo.GitOps
import se.personal.adoprreview.repo.ResolvedRepo
import se.personal.adoprreview.state.AdoSettings
import se.personal.adoprreview.state.PrReviewService
import se.personal.adoprreview.state.PrTemplates
import se.personal.adoprreview.state.PrWatcher
import se.personal.adoprreview.toolwindow.StartTicketDialog

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
        PrReviewService.getInstance(project).scope.launch(Dispatchers.EDT) {
            try {
                start(project, repo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AdoNotifications.error(project, "Couldn't start the ticket", e)
            }
        }
    }

    private suspend fun start(project: Project, repo: ResolvedRepo) {
        if (!checkClean(project, repo)) return

        val session = JiraSignIn.session(project, repo.remote.org) ?: return
        val jira = JiraClient.getInstance()
        val ado = AdoClient.getInstance()
        val remote = repo.remote

        val (issues, defaultBranch) = withBackgroundProgress(project, "Loading your Jira tickets") {
            coroutineScope {
                val fetch = async(Dispatchers.IO) { GitOps.fetch(project, repo) }
                val defaultBranch = async { ado.repository(remote).defaultBranch?.removePrefix("refs/heads/") }
                val issues = jira.myOpenIssues(session.site, session.login)
                fetch.await()
                issues to defaultBranch.await()
            }
        }

        val settings = AdoSettings.getInstance()
        val repoKey = "${remote.org}/${remote.project}/${remote.repo}".lowercase()
        val targets = CreatePrAction.targetBranches(repo, null, defaultBranch)
        val dialog = StartTicketDialog(
            project,
            issues = issues,
            targetBranches = targets,
            defaultBranch = defaultBranch,
            defaultTarget = settings.lastTarget(repoKey)?.takeIf { it in targets } ?: defaultBranch,
            assign = settings.startAssignToMe,
            moveToInProgress = settings.startMoveToInProgress,
            scope = PrReviewService.getInstance(project).scope,
            lookup = { key -> jira.issue(session.site, session.login, key) },
        )
        if (!dialog.showAndGet()) return
        val issue = dialog.issue ?: return
        val branch = dialog.branchName
        val target = dialog.targetBranch
        settings.setLastTarget(repoKey, target)
        settings.startAssignToMe = dialog.assignToMe
        settings.startMoveToInProgress = dialog.moveToInProgress

        // A branch for this ticket already exists: switch to it rather than make a second one.
        val git = repo.gitRepository
        val local = git.branches.localBranches.map { it.name }
        val onRemote = git.branches.remoteBranches.filter { it.remote == repo.gitRemote }.map { it.nameForRemoteOperations }
        val existing = TicketBranches.branchesFor(issue.key, local + onRemote)
        if (existing.isNotEmpty()) {
            checkoutExisting(project, repo, issue.key, existing.firstOrNull { it in local } ?: existing.first(), local)
            return
        }

        if (!checkClean(project, repo)) return
        val title = TicketBranches.prTitle(issue.key, issue.summary)
        val pr = withBackgroundProgress(project, "Starting ${issue.key}") {
            withContext(Dispatchers.IO) {
                GitOps.checkoutNewBranch(project, repo, branch, "${repo.gitRemote.name}/$target")
                GitOps.emptyCommit(project, repo, title)
                GitOps.push(project, repo, branch)
            }
            ado.createPullRequest(
                remote,
                NewPullRequest(
                    sourceRefName = "refs/heads/$branch",
                    targetRefName = "refs/heads/$target",
                    title = title,
                    description = PrTemplates.description(branch, session.site),
                    isDraft = true,
                ),
            )
        }

        val jiraProblems = updateJira(project, session, issue, dialog.assignToMe, dialog.moveToInProgress)
        AdoNotifications.info(project, "Started ${issue.key}", "On $branch · draft PR #${pr.pullRequestId}", remote.webUrl(pr.pullRequestId))
        if (jiraProblems.isNotEmpty()) warn(project, "Jira wasn't fully updated", jiraProblems.joinToString("<br>"))
        PrWatcher.getInstance(project).listPanel?.refreshAndSelect(pr.pullRequestId)
    }

    /** Saves editors first, then refuses to go on while tracked files have changes. */
    private suspend fun checkClean(project: Project, repo: ResolvedRepo): Boolean {
        withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().saveAllDocuments() }
        val changed = withContext(Dispatchers.IO) { GitOps.uncommittedFiles(project, repo) }
        if (changed.isEmpty()) return true
        val shown = changed.take(8).joinToString("\n") { "  $it" } + if (changed.size > 8) "\n  … and ${changed.size - 8} more" else ""
        withContext(Dispatchers.EDT) {
            Messages.showWarningDialog(
                project,
                "You have uncommitted changes. Commit or shelve them first, so the new branch starts clean.\n\n$shown",
                "Start Jira Ticket",
            )
        }
        return false
    }

    private suspend fun checkoutExisting(project: Project, repo: ResolvedRepo, key: String, branch: String, local: List<String>) {
        val current = repo.gitRepository.currentBranchName
        val ado = AdoClient.getInstance()
        if (branch != current) {
            val answer = Messages.showOkCancelDialog(
                project, "$key already has a branch: $branch. Check it out instead of creating a new one?",
                "Start Jira Ticket", "Check Out", "Cancel", Messages.getQuestionIcon(),
            )
            if (answer != Messages.OK) return
            if (!checkClean(project, repo)) return
            withBackgroundProgress(project, "Checking out $branch") {
                withContext(Dispatchers.IO) {
                    if (branch in local) GitOps.checkoutExisting(project, repo, branch) else GitOps.checkoutRemote(project, repo, branch)
                }
            }
        }
        val pr = withBackgroundProgress(project, "Looking for a pull request from $branch") {
            ado.activePullRequestFrom(repo.remote, branch)
        }
        if (pr != null) {
            PrWatcher.getInstance(project).listPanel?.refreshAndSelect(pr.pullRequestId)
            AdoNotifications.info(project, "On $branch", "Its pull request is #${pr.pullRequestId}.", repo.remote.webUrl(pr.pullRequestId))
        } else {
            AdoNotifications.info(project, "On $branch", "No active pull request from it yet. Use + to create one.")
        }
    }

    /** Assigns and transitions as asked; returns what failed, as messages. Jira trouble never undoes the branch or PR. */
    private suspend fun updateJira(project: Project, session: JiraSession, issue: JiraIssue, assign: Boolean, inProgress: Boolean): List<String> {
        val jira = JiraClient.getInstance()
        val problems = mutableListOf<String>()
        withBackgroundProgress(project, "Updating ${issue.key} in Jira") {
            if (assign && issue.assigneeId != session.me.accountId) {
                runCatching { jira.assign(session.site, session.login, issue.key, session.me.accountId) }
                    .onFailure { problems += "Couldn't assign ${issue.key} to you: ${it.message}" }
            }
            if (inProgress && issue.statusCategory != "indeterminate") {
                runCatching {
                    val transition = TicketBranches.inProgressTransition(issue, jira.transitions(session.site, session.login, issue.key))
                    if (transition == null) {
                        problems += "${issue.key} has no transition to an In Progress status from ${issue.statusName ?: "its status"}."
                    } else {
                        jira.transition(session.site, session.login, issue.key, transition.id)
                    }
                }.onFailure { problems += "Couldn't move ${issue.key} to In Progress: ${it.message}" }
            }
        }
        return problems
    }

    private fun warn(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("ADO PR Review")
            .createNotification(title, content, NotificationType.WARNING)
            .notify(project)
    }
}
