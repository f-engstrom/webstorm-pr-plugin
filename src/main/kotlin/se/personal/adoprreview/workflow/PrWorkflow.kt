package se.personal.adoprreview.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import se.personal.adoprreview.jira.JiraIssue
import se.personal.adoprreview.jira.TicketBranches
import se.personal.adoprreview.jira.Transition
import se.personal.adoprreview.repo.GitOps.PushState
import se.personal.adoprreview.state.PrTemplates

// ---- Ports. Production adapters live in IdeWorkflow.kt; tests use in-memory fakes. ----

/** The open repository, as the workflow sees it. Branch names never carry `refs/heads/` or the remote prefix. */
interface WorkflowGit {
    val remoteName: String
    fun currentBranch(): String?
    fun localBranches(): List<String>
    /** Branches on the PR's remote, without the remote prefix. */
    fun remoteBranches(): List<String>
    suspend fun fetch()
    suspend fun pushState(branch: String): PushState
    suspend fun push(branch: String)
    suspend fun lastCommitSubject(): String?
    suspend fun uncommittedFiles(): List<String>
    /** Creates [branch] from [startPoint] (e.g. `origin/develop`) without tracking, and switches to it. */
    suspend fun createBranch(branch: String, startPoint: String)
    suspend fun switchTo(branch: String)
    /** Creates a local [branch] tracking the remote one, and switches to it. */
    suspend fun trackRemote(branch: String)
    suspend fun emptyCommit(message: String)
    suspend fun deleteBranch(branch: String)
}

/** The Azure DevOps repository the PRs live in. */
interface WorkflowAdo {
    suspend fun defaultBranch(): String?
    suspend fun activePullRequestFrom(branch: String): Int?
    suspend fun createPullRequest(source: String, target: String, title: String, description: String, draft: Boolean): CreatedPr
}

data class CreatedPr(val id: Int, val title: String)

/** The Jira site for the repo's org. */
interface TicketTracker {
    val site: String
    /** Null when unknown (no signed-in session); then "already assigned to me" can't be checked. */
    val myAccountId: String?
    suspend fun summary(key: String): String?
    suspend fun assignToMe(key: String)
    suspend fun transitions(key: String): List<Transition>
    suspend fun transition(key: String, transitionId: String)
}

/** The target branch last used in this repo. */
interface TargetMemory {
    fun last(): String?
    fun remember(branch: String)
}

enum class PushChoice { PUSH, DONT_PUSH, CANCEL }

/** What the user is offered in the Create PR dialog. */
data class PrProposal(
    val source: String,
    val targets: List<String>,
    val defaultBranch: String?,
    val preselectedTarget: String?,
    val title: String,
)

/** What the user asked for in the Create PR dialog. */
data class PrRequest(val target: String, val title: String, val description: String, val draft: Boolean)

/** Everything that asks or tells the user something, or shows progress. */
interface WorkflowUi {
    suspend fun <T> progress(title: String, block: suspend () -> T): T
    suspend fun saveDocuments()
    /** Only asked when [state] isn't up to date. */
    suspend fun confirmPush(branch: String, remote: String, state: PushState): PushChoice
    suspend fun composePr(proposal: PrProposal): PrRequest?
    suspend fun confirmSwitch(key: String, branch: String): Boolean
    suspend fun showUncommitted(files: List<String>)
}

// ---- Inputs and outcomes. ----

data class Targets(val branches: List<String>, val defaultBranch: String?, val preselected: String?)

data class StartRequest(
    val issue: JiraIssue,
    val branch: String,
    val target: String,
    val assignToMe: Boolean,
    val moveToInProgress: Boolean,
)

sealed interface CreateOutcome {
    data class AlreadyOpen(val prId: Int) : CreateOutcome
    /** [fromStaleRemote]: the user chose not to push, so the PR shows the remote's older head of the branch. */
    data class Created(val prId: Int, val title: String, val fromStaleRemote: Boolean) : CreateOutcome
    data object Cancelled : CreateOutcome
}

sealed interface StartOutcome {
    data class Started(val branch: String, val prId: Int, val jiraProblems: List<String>) : StartOutcome
    /** The ticket already had a branch; the user is now on it. */
    data class SwitchedToExisting(val branch: String, val prId: Int?) : StartOutcome
    /** The branch is on the remote but the PR couldn't be created; it's kept so the user can create the PR with +. */
    data class PushedWithoutPr(val branch: String, val error: Throwable) : StartOutcome
    /** Nothing is left behind: a half-made branch was removed and the user is back where they started. */
    data class Failed(val message: String, val error: Throwable) : StartOutcome
    data object Cancelled : StartOutcome
}

/**
 * Turns branches into pull requests: Create PR from the current branch, and Start Ticket (branch, empty commit, push,
 * draft PR, Jira update), or switching to the ticket's existing branch. Owns the decisions in between: whether to
 * push, which target to offer, what to undo when a step fails, and what Jira trouble means.
 */
class PrWorkflow(
    private val git: WorkflowGit,
    private val ado: WorkflowAdo,
    private val memory: TargetMemory,
    private val ui: WorkflowUi,
) {

    /** Create PR from [branch]. [tracker] is only used to read the ticket summary for the title; it may be null. */
    suspend fun createPr(branch: String, tracker: TicketTracker?): CreateOutcome {
        val existing = ui.progress("Checking for a pull request from $branch") { ado.activePullRequestFrom(branch) }
        if (existing != null) return CreateOutcome.AlreadyOpen(existing)

        // ADO needs the branch on the remote, so an unpushed branch must be pushed.
        val state = ui.progress("Checking $branch on ${git.remoteName}") { git.pushState(branch) }
        val choice = if (state == PushState.UP_TO_DATE) PushChoice.DONT_PUSH else ui.confirmPush(branch, git.remoteName, state)
        if (choice == PushChoice.CANCEL) return CreateOutcome.Cancelled
        if (choice == PushChoice.DONT_PUSH && state == PushState.NOT_PUSHED) return CreateOutcome.Cancelled
        if (choice == PushChoice.PUSH) ui.progress("Pushing $branch") { git.push(branch) }

        val (defaultBranch, lastCommit) = ui.progress("Preparing pull request") {
            coroutineScope {
                val commit = async { git.lastCommitSubject() }
                ado.defaultBranch() to commit.await()
            }
        }
        val summary = ticketSummary(branch, tracker)
        val targets = targets(branch, defaultBranch)
        val request = ui.composePr(
            PrProposal(branch, targets.branches, defaultBranch, targets.preselected, PrTemplates.title(branch, lastCommit, summary)),
        ) ?: return CreateOutcome.Cancelled
        memory.remember(request.target)

        val created = ui.progress("Creating pull request") {
            ado.createPullRequest(branch, request.target, request.title, request.description, request.draft)
        }
        return CreateOutcome.Created(created.id, created.title, fromStaleRemote = state == PushState.DIFFERS && choice == PushChoice.DONT_PUSH)
    }

    /** Fetches, so remote branches are current, and returns the targets to offer in the Start Ticket dialog. */
    suspend fun prepareStart(): Targets = coroutineScope {
        val fetch = async { git.fetch() }
        val defaultBranch = ado.defaultBranch()
        fetch.await()
        targets(null, defaultBranch)
    }

    /** Saves editors, then returns false (after telling the user) while tracked files have changes. */
    suspend fun ensureClean(): Boolean {
        ui.saveDocuments()
        val changed = git.uncommittedFiles()
        if (changed.isEmpty()) return true
        ui.showUncommitted(changed)
        return false
    }

    suspend fun startTicket(request: StartRequest, tracker: TicketTracker): StartOutcome {
        val issue = request.issue
        memory.remember(request.target)

        val local = git.localBranches()
        val existing = TicketBranches.branchesFor(issue.key, local + git.remoteBranches())
        if (existing.isNotEmpty()) return switchToTicketBranch(issue.key, existing.firstOrNull { it in local } ?: existing.first(), local)

        if (!ensureClean()) return StartOutcome.Cancelled
        val branch = request.branch
        val title = TicketBranches.prTitle(issue.key, issue.summary)
        val original = git.currentBranch()

        val outcome: StartOutcome = ui.progress("Starting ${issue.key}") {
            git.createBranch(branch, "${git.remoteName}/${request.target}")
            try {
                git.emptyCommit(title)
                git.push(branch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@progress StartOutcome.Failed(rollBack(branch, original, e), e)
            }
            try {
                val pr = ado.createPullRequest(branch, request.target, title, PrTemplates.description(branch, tracker.site), draft = true)
                StartOutcome.Started(branch, pr.id, jiraProblems = emptyList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StartOutcome.PushedWithoutPr(branch, e)
            }
        }
        if (outcome !is StartOutcome.Started) return outcome

        // Jira trouble never undoes the branch or PR.
        return outcome.copy(jiraProblems = updateJira(issue, tracker, request.assignToMe, request.moveToInProgress))
    }

    private suspend fun switchToTicketBranch(key: String, branch: String, local: List<String>): StartOutcome {
        if (branch != git.currentBranch()) {
            if (!ui.confirmSwitch(key, branch)) return StartOutcome.Cancelled
            if (!ensureClean()) return StartOutcome.Cancelled
            ui.progress("Checking out $branch") { if (branch in local) git.switchTo(branch) else git.trackRemote(branch) }
        }
        val pr = ui.progress("Looking for a pull request from $branch") { ado.activePullRequestFrom(branch) }
        return StartOutcome.SwitchedToExisting(branch, pr)
    }

    /** Back to [original] and the new [branch] deleted; returns the message describing the failure and the cleanup. */
    private suspend fun rollBack(branch: String, original: String?, error: Exception): String {
        val reason = error.message ?: error.javaClass.simpleName
        if (original == null) return "$reason\n\n$branch was left in place: there was no branch to go back to."
        return try {
            git.switchTo(original)
            git.deleteBranch(branch)
            "$reason\n\n$branch was removed and you're back on $original."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "$reason\n\nCouldn't remove $branch: ${e.message}"
        }
    }

    private suspend fun ticketSummary(branch: String, tracker: TicketTracker?): String? {
        val key = PrTemplates.ticketKey(branch) ?: return null
        tracker ?: return null
        return try {
            ui.progress("Reading $key from Jira") { tracker.summary(key) }?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun updateJira(issue: JiraIssue, tracker: TicketTracker, assign: Boolean, inProgress: Boolean): List<String> {
        val problems = mutableListOf<String>()
        ui.progress("Updating ${issue.key} in Jira") {
            if (assign && (tracker.myAccountId == null || issue.assigneeId != tracker.myAccountId)) {
                attempt({ problems += "Couldn't assign ${issue.key} to you: $it" }) { tracker.assignToMe(issue.key) }
            }
            if (inProgress && issue.statusCategory != "indeterminate") {
                attempt({ problems += "Couldn't move ${issue.key} to In Progress: $it" }) {
                    val transition = TicketBranches.inProgressTransition(issue, tracker.transitions(issue.key))
                    if (transition == null) {
                        problems += "${issue.key} has no transition to an In Progress status from ${issue.statusName ?: "its status"}."
                    } else {
                        tracker.transition(issue.key, transition.id)
                    }
                }
            }
        }
        return problems
    }

    private suspend fun attempt(onFailure: (String?) -> Unit, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(e.message)
        }
    }

    private fun targets(source: String?, defaultBranch: String?): Targets {
        val branches = rankTargets(git.remoteBranches(), source, defaultBranch)
        return Targets(branches, defaultBranch, memory.last()?.takeIf { it in branches } ?: defaultBranch)
    }

    companion object {
        /** The default branch first, then develop/main-like branches, then the rest alphabetically. */
        fun rankTargets(remoteBranches: List<String>, source: String?, defaultBranch: String?): List<String> {
            fun rank(name: String) = when {
                name == defaultBranch -> 0
                name.startsWith("develop") || name == "main" || name == "master" -> 1
                else -> 2
            }
            return remoteBranches.filter { it != "HEAD" && it != source }.distinct().sortedWith(compareBy(::rank, { it }))
        }
    }
}
