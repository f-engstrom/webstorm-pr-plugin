package se.personal.adoprreview.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.personal.adoprreview.AdoNotifications
import se.personal.adoprreview.api.AdoClient
import se.personal.adoprreview.api.dto.NewPullRequest
import se.personal.adoprreview.repo.GitOps
import se.personal.adoprreview.repo.ResolvedRepo
import se.personal.adoprreview.state.AdoSettings
import se.personal.adoprreview.state.PrReviewService
import se.personal.adoprreview.state.PrTemplates
import se.personal.adoprreview.toolwindow.CreatePrDialog
import se.personal.adoprreview.toolwindow.PrListPanel

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
        PrReviewService.getInstance(project).scope.launch(Dispatchers.EDT) {
            try {
                createFrom(project, repo, branch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AdoNotifications.error(project, "Couldn't create pull request", e)
            }
        }
    }

    private suspend fun createFrom(project: Project, repo: ResolvedRepo, branch: String) {
        val client = AdoClient.getInstance()
        val remote = repo.remote

        val existing = withBackgroundProgress(project, "Checking for a pull request from $branch") {
            client.activePullRequestFrom(remote, branch)
        }
        if (existing != null) {
            AdoNotifications.info(
                project, "Pull request already open", "#${existing.pullRequestId} is open from $branch.",
                remote.webUrl(existing.pullRequestId),
            )
            panel.refreshAndSelect(existing.pullRequestId)
            return
        }

        if (!pushIfNeeded(project, repo, branch)) return

        val (defaultBranch, lastCommit) = withBackgroundProgress(project, "Preparing pull request") {
            client.repository(remote).defaultBranch?.removePrefix("refs/heads/") to
                withContext(Dispatchers.IO) { GitOps.lastCommitSubject(project, repo) }
        }
        val settings = AdoSettings.getInstance()
        val repoKey = "${remote.org}/${remote.project}/${remote.repo}".lowercase()
        val targets = targetBranches(repo, branch, defaultBranch)
        val dialog = CreatePrDialog(
            project,
            sourceBranch = branch,
            targetBranches = targets,
            defaultTarget = settings.lastTarget(repoKey)?.takeIf { it in targets } ?: defaultBranch,
            title = PrTemplates.title(branch, lastCommit),
            jiraSite = settings.jiraSite(remote.org),
            draft = settings.createAsDraft,
        )
        if (!dialog.showAndGet()) return

        settings.setJiraSite(remote.org, dialog.jiraSite)
        settings.setLastTarget(repoKey, dialog.targetBranch)
        settings.createAsDraft = dialog.isDraft

        val created = withBackgroundProgress(project, "Creating pull request") {
            client.createPullRequest(
                remote,
                NewPullRequest(
                    sourceRefName = "refs/heads/$branch",
                    targetRefName = "refs/heads/${dialog.targetBranch}",
                    title = dialog.prTitle,
                    description = dialog.prDescription,
                    isDraft = dialog.isDraft,
                ),
            )
        }
        AdoNotifications.info(project, "Pull request created", "#${created.pullRequestId} ${created.title}", remote.webUrl(created.pullRequestId))
        panel.refreshAndSelect(created.pullRequestId)
    }

    /** Returns false if the user cancelled. ADO needs the branch on the remote, so an unpushed branch must be pushed. */
    private suspend fun pushIfNeeded(project: Project, repo: ResolvedRepo, branch: String): Boolean {
        val state = withBackgroundProgress(project, "Checking $branch on ${repo.gitRemote.name}") {
            withContext(Dispatchers.IO) { GitOps.pushState(project, repo, branch) }
        }
        val remoteName = repo.gitRemote.name
        val push = when (state) {
            GitOps.PushState.UP_TO_DATE -> false
            GitOps.PushState.NOT_PUSHED -> {
                val answer = Messages.showOkCancelDialog(
                    project, "'$branch' isn't on $remoteName yet. Push it and create the pull request?",
                    "Create Pull Request", "Push", "Cancel", Messages.getQuestionIcon(),
                )
                if (answer != Messages.OK) return false
                true
            }
            GitOps.PushState.DIFFERS -> when (Messages.showYesNoCancelDialog(
                project, "'$branch' on $remoteName isn't at your latest commit. Push first?",
                "Create Pull Request", "Push", "Don't Push", "Cancel", Messages.getQuestionIcon(),
            )) {
                Messages.YES -> true
                Messages.NO -> false
                else -> return false
            }
        }
        if (push) {
            withBackgroundProgress(project, "Pushing $branch") {
                withContext(Dispatchers.IO) { GitOps.push(project, repo, branch) }
            }
        }
        return true
    }

    /** Branches on the PR's remote, with the default branch and develop/main-like branches first. */
    private fun targetBranches(repo: ResolvedRepo, source: String, defaultBranch: String?): List<String> {
        val names = repo.gitRepository.branches.remoteBranches
            .filter { it.remote == repo.gitRemote }
            .map { it.nameForRemoteOperations }
            .filter { it != "HEAD" && it != source }
            .distinct()
        fun rank(name: String) = when {
            name == defaultBranch -> 0
            name.startsWith("develop") || name == "main" || name == "master" -> 1
            else -> 2
        }
        return names.sortedWith(compareBy(::rank, { it }))
    }
}
