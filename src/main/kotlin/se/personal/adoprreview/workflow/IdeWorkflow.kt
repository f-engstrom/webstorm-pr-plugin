package se.personal.adoprreview.workflow

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.personal.adoprreview.api.AdoClient
import se.personal.adoprreview.api.dto.NewPullRequest
import se.personal.adoprreview.jira.JiraAuth
import se.personal.adoprreview.jira.JiraClient
import se.personal.adoprreview.jira.JiraSession
import se.personal.adoprreview.jira.Transition
import se.personal.adoprreview.repo.AdoRemote
import se.personal.adoprreview.repo.GitOps
import se.personal.adoprreview.repo.ResolvedRepo
import se.personal.adoprreview.state.AdoSettings
import se.personal.adoprreview.toolwindow.CreatePrDialog

/** [PrWorkflow] wired to git4idea, Azure DevOps, the plugin settings and IDE dialogs for [repo]. */
fun ideWorkflow(project: Project, repo: ResolvedRepo, dialogTitle: String): PrWorkflow = PrWorkflow(
    IdeGit(project, repo),
    AdoRepository(repo.remote),
    SettingsTargetMemory(repo.remote),
    IdeWorkflowUi(project, repo.remote.org, dialogTitle),
)

/** A tracker for a signed-in Jira session (Start Ticket). */
fun jiraTracker(session: JiraSession): TicketTracker = JiraTracker(session.site, session.login, session.me.accountId)

/** A tracker from the saved Jira login, without prompting (Create PR's title); null when there's none. */
suspend fun savedJiraTracker(org: String): TicketTracker? =
    se.personal.adoprreview.jira.JiraSignIn.saved(org)?.let { (site, login) -> JiraTracker(site, login, myAccountId = null) }

private class IdeGit(private val project: Project, private val repo: ResolvedRepo) : WorkflowGit {
    override val remoteName: String get() = repo.gitRemote.name
    override fun currentBranch(): String? = repo.gitRepository.currentBranchName
    override fun localBranches(): List<String> = repo.gitRepository.branches.localBranches.map { it.name }
    override fun remoteBranches(): List<String> =
        repo.gitRepository.branches.remoteBranches.filter { it.remote == repo.gitRemote }.map { it.nameForRemoteOperations }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    override suspend fun fetch() = io { GitOps.fetch(project, repo) }
    override suspend fun pushState(branch: String) = io { GitOps.pushState(project, repo, branch) }
    override suspend fun push(branch: String) = io { GitOps.push(project, repo, branch) }
    override suspend fun lastCommitSubject() = io { GitOps.lastCommitSubject(project, repo) }
    override suspend fun uncommittedFiles() = io { GitOps.uncommittedFiles(project, repo) }
    override suspend fun createBranch(branch: String, startPoint: String) = io { GitOps.checkoutNewBranch(project, repo, branch, startPoint) }
    override suspend fun switchTo(branch: String) = io { GitOps.checkoutExisting(project, repo, branch) }
    override suspend fun trackRemote(branch: String) = io { GitOps.checkoutRemote(project, repo, branch) }
    override suspend fun emptyCommit(message: String) = io { GitOps.emptyCommit(project, repo, message) }
    override suspend fun deleteBranch(branch: String) = io { GitOps.deleteBranch(project, repo, branch) }
}

private class AdoRepository(private val remote: AdoRemote) : WorkflowAdo {
    private val client get() = AdoClient.getInstance()

    override suspend fun defaultBranch(): String? = client.repository(remote).defaultBranch?.removePrefix("refs/heads/")
    override suspend fun activePullRequestFrom(branch: String): Int? = client.activePullRequestFrom(remote, branch)?.pullRequestId

    override suspend fun createPullRequest(source: String, target: String, title: String, description: String, draft: Boolean): CreatedPr {
        val pr = client.createPullRequest(
            remote,
            NewPullRequest("refs/heads/$source", "refs/heads/$target", title, description, draft),
        )
        return CreatedPr(pr.pullRequestId, pr.title)
    }
}

private class JiraTracker(override val site: String, private val login: JiraAuth.Login, override val myAccountId: String?) : TicketTracker {
    private val client get() = JiraClient.getInstance()

    override suspend fun summary(key: String): String? = client.issue(site, login, key).summary
    override suspend fun assignToMe(key: String) =
        client.assign(site, login, key, myAccountId ?: client.myself(site, login).accountId)
    override suspend fun transitions(key: String): List<Transition> = client.transitions(site, login, key)
    override suspend fun transition(key: String, transitionId: String) = client.transition(site, login, key, transitionId)
}

private class SettingsTargetMemory(remote: AdoRemote) : TargetMemory {
    private val key = "${remote.org}/${remote.project}/${remote.repo}".lowercase()
    override fun last(): String? = AdoSettings.getInstance().lastTarget(key)
    override fun remember(branch: String) = AdoSettings.getInstance().setLastTarget(key, branch)
}

private class IdeWorkflowUi(private val project: Project, private val org: String, private val title: String) : WorkflowUi {

    override suspend fun <T> progress(title: String, block: suspend () -> T): T = withBackgroundProgress(project, title) { block() }

    override suspend fun saveDocuments() = withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().saveAllDocuments() }

    override suspend fun confirmPush(branch: String, remote: String, state: GitOps.PushState): PushChoice = withContext(Dispatchers.EDT) {
        when (state) {
            GitOps.PushState.UP_TO_DATE -> PushChoice.DONT_PUSH
            GitOps.PushState.NOT_PUSHED -> {
                val answer = Messages.showOkCancelDialog(
                    project, "'$branch' isn't on $remote yet. Push it and create the pull request?",
                    title, "Push", "Cancel", Messages.getQuestionIcon(),
                )
                if (answer == Messages.OK) PushChoice.PUSH else PushChoice.CANCEL
            }
            GitOps.PushState.DIFFERS -> when (Messages.showYesNoCancelDialog(
                project, "'$branch' on $remote isn't at your latest commit. Push first?",
                title, "Push", "Don't Push", "Cancel", Messages.getQuestionIcon(),
            )) {
                Messages.YES -> PushChoice.PUSH
                Messages.NO -> PushChoice.DONT_PUSH
                else -> PushChoice.CANCEL
            }
        }
    }

    /** The dialog also edits the org's Jira site and the draft default; those are saved when the user confirms. */
    override suspend fun composePr(proposal: PrProposal): PrRequest? = withContext(Dispatchers.EDT) {
        val settings = AdoSettings.getInstance()
        val dialog = CreatePrDialog(
            project,
            sourceBranch = proposal.source,
            targetBranches = proposal.targets,
            defaultBranch = proposal.defaultBranch,
            defaultTarget = proposal.preselectedTarget,
            title = proposal.title,
            jiraSite = settings.jiraSite(org),
            draft = settings.createAsDraft,
        )
        if (!dialog.showAndGet()) return@withContext null
        settings.setJiraSite(org, dialog.jiraSite)
        settings.createAsDraft = dialog.isDraft
        PrRequest(dialog.targetBranch, dialog.prTitle, dialog.prDescription, dialog.isDraft)
    }

    override suspend fun confirmSwitch(key: String, branch: String): Boolean = withContext(Dispatchers.EDT) {
        Messages.showOkCancelDialog(
            project, "$key already has a branch: $branch. Check it out instead of creating a new one?",
            title, "Check Out", "Cancel", Messages.getQuestionIcon(),
        ) == Messages.OK
    }

    override suspend fun showUncommitted(files: List<String>) {
        val shown = files.take(8).joinToString("\n") { "  $it" } + if (files.size > 8) "\n  … and ${files.size - 8} more" else ""
        withContext(Dispatchers.EDT) {
            Messages.showWarningDialog(
                project,
                "You have uncommitted changes. Commit or shelve them first, so the new branch starts clean.\n\n$shown",
                title,
            )
        }
    }
}
