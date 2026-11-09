package se.personal.adoprreview.workflow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.personal.adoprreview.jira.JiraIssue
import se.personal.adoprreview.jira.JiraIssueFields
import se.personal.adoprreview.jira.JiraStatus
import se.personal.adoprreview.jira.JiraUser
import se.personal.adoprreview.jira.StatusCategory
import se.personal.adoprreview.jira.Transition
import se.personal.adoprreview.repo.GitOps.PushState

class PrWorkflowTest {

    // ---- Fakes ----

    private class FakeGit(
        var current: String? = "develop",
        val local: MutableList<String> = mutableListOf("develop"),
        val remote: MutableList<String> = mutableListOf("HEAD", "develop", "main", "release/1"),
        var pushState: PushState = PushState.UP_TO_DATE,
        var uncommitted: List<String> = emptyList(),
        var failPush: Boolean = false,
        var failCommit: Boolean = false,
    ) : WorkflowGit {
        val log = mutableListOf<String>()
        val commits = mutableMapOf<String, Int>()
        override val remoteName = "origin"
        override fun currentBranch() = current
        override fun localBranches() = local.toList()
        override fun remoteBranches() = remote.toList()
        override suspend fun fetch() { log += "fetch" }
        override suspend fun pushState(branch: String) = pushState
        override suspend fun push(branch: String) {
            log += "push $branch"
            if (failPush) error("rejected")
            remote += branch
        }
        override suspend fun lastCommitSubject() = "Last commit"
        override suspend fun uncommittedFiles() = uncommitted
        override suspend fun createBranch(branch: String, startPoint: String) {
            log += "create $branch from $startPoint"
            local += branch
            current = branch
        }
        override suspend fun switchTo(branch: String) { log += "switch $branch"; current = branch }
        override suspend fun trackRemote(branch: String) { log += "track $branch"; local += branch; current = branch }
        override suspend fun emptyCommit(message: String) {
            log += "commit $message"
            if (failCommit) error("hook failed")
        }
        override suspend fun deleteBranch(branch: String) { log += "delete $branch"; local -= branch }
    }

    private class FakeAdo(val openFrom: Map<String, Int> = emptyMap(), var failCreate: Boolean = false) : WorkflowAdo {
        val created = mutableListOf<List<Any>>()
        override suspend fun defaultBranch() = "develop"
        override suspend fun activePullRequestFrom(branch: String) = openFrom[branch]
        override suspend fun createPullRequest(source: String, target: String, title: String, description: String, draft: Boolean): CreatedPr {
            if (failCreate) error("ADO down")
            created += listOf(source, target, title, description, draft)
            return CreatedPr(100 + created.size, title)
        }
    }

    private class FakeTracker(
        val transitions: List<Transition> = listOf(Transition("31", "In Progress", JiraStatus("In Progress", StatusCategory("indeterminate")))),
        val failAssign: Boolean = false,
    ) : TicketTracker {
        val log = mutableListOf<String>()
        override val site = "https://acme.atlassian.net"
        override val myAccountId = "me"
        override suspend fun summary(key: String) = "Fix the forms"
        override suspend fun assignToMe(key: String) { if (failAssign) error("no permission"); log += "assign $key" }
        override suspend fun transitions(key: String) = transitions
        override suspend fun transition(key: String, transitionId: String) { log += "transition $key $transitionId" }
    }

    private class FakeMemory(var value: String? = null) : TargetMemory {
        override fun last() = value
        override fun remember(branch: String) { value = branch }
    }

    private class FakeUi(
        val push: PushChoice = PushChoice.PUSH,
        val compose: (PrProposal) -> PrRequest? = { PrRequest(it.preselectedTarget!!, it.title, "", draft = false) },
        val switch: Boolean = true,
    ) : WorkflowUi {
        val asked = mutableListOf<String>()
        var proposal: PrProposal? = null
        override suspend fun <T> progress(title: String, block: suspend () -> T): T = block()
        override suspend fun saveDocuments() {}
        override suspend fun confirmPush(branch: String, remote: String, state: PushState): PushChoice { asked += "push $state"; return push }
        override suspend fun composePr(proposal: PrProposal): PrRequest? { this.proposal = proposal; return compose(proposal) }
        override suspend fun confirmSwitch(key: String, branch: String): Boolean { asked += "switch $branch"; return switch }
        override suspend fun showUncommitted(files: List<String>) { asked += "dirty $files" }
    }

    private fun issue(key: String = "ABC-1", assignee: String? = null, category: String = "new") =
        JiraIssue(key, JiraIssueFields("Fix the forms", status = JiraStatus("To Do", StatusCategory(category)), assignee = assignee?.let { JiraUser(it) }))

    private fun request(branch: String = "feature/ABC-1", issue: JiraIssue = issue()) =
        StartRequest(issue, branch, "develop", assignToMe = true, moveToInProgress = true)

    // ---- Create PR ----

    @Test
    fun `an open PR from the branch is returned without asking anything`() = runBlocking {
        val ui = FakeUi()
        val outcome = PrWorkflow(FakeGit(), FakeAdo(openFrom = mapOf("feature/ABC-1" to 7)), FakeMemory(), ui).createPr("feature/ABC-1", null)
        assertEquals(CreateOutcome.AlreadyOpen(7), outcome)
        assertTrue(ui.asked.isEmpty())
    }

    @Test
    fun `an unpushed branch is pushed before the PR is created`() = runBlocking {
        val git = FakeGit(pushState = PushState.NOT_PUSHED)
        val ado = FakeAdo()
        val outcome = PrWorkflow(git, ado, FakeMemory(), FakeUi()).createPr("feature/ABC-1", FakeTracker())
        assertEquals(CreateOutcome.Created(101, "ABC-1 Fix the forms", fromStaleRemote = false), outcome)
        assertEquals(listOf("push feature/ABC-1"), git.log)
    }

    @Test
    fun `declining to push a differing branch is reported as stale`() = runBlocking {
        val git = FakeGit(pushState = PushState.DIFFERS)
        val outcome = PrWorkflow(git, FakeAdo(), FakeMemory(), FakeUi(push = PushChoice.DONT_PUSH)).createPr("feature/ABC-1", null)
        assertTrue((outcome as CreateOutcome.Created).fromStaleRemote)
        assertTrue(git.log.isEmpty())
    }

    @Test
    fun `cancelling the push creates nothing`() = runBlocking {
        val ado = FakeAdo()
        val outcome = PrWorkflow(FakeGit(pushState = PushState.NOT_PUSHED), ado, FakeMemory(), FakeUi(push = PushChoice.CANCEL)).createPr("x", null)
        assertEquals(CreateOutcome.Cancelled, outcome)
        assertTrue(ado.created.isEmpty())
    }

    @Test
    fun `targets rank the default branch first and preselect the remembered one`() = runBlocking {
        val ui = FakeUi()
        val memory = FakeMemory("release/1")
        PrWorkflow(FakeGit(), FakeAdo(), memory, ui).createPr("feature/ABC-1", null)
        val proposal = ui.proposal!!
        assertEquals(listOf("develop", "main", "release/1"), proposal.targets)
        assertEquals("release/1", proposal.preselectedTarget)
        assertEquals("ABC-1 ", proposal.title)
    }

    @Test
    fun `a remembered target that no longer exists falls back to the default branch`() = runBlocking {
        val ui = FakeUi()
        PrWorkflow(FakeGit(), FakeAdo(), FakeMemory("gone"), ui).createPr("feature/ABC-1", null)
        assertEquals("develop", ui.proposal!!.preselectedTarget)
    }

    // ---- Start Ticket ----

    @Test
    fun `starting a ticket branches, commits, pushes, opens a draft PR and updates Jira`() = runBlocking {
        val git = FakeGit()
        val ado = FakeAdo()
        val tracker = FakeTracker()
        val outcome = PrWorkflow(git, ado, FakeMemory(), FakeUi()).startTicket(request(), tracker)
        assertEquals(StartOutcome.Started("feature/ABC-1", 101, emptyList()), outcome)
        assertEquals(
            listOf("create feature/ABC-1 from origin/develop", "commit ABC-1 Fix the forms", "push feature/ABC-1"),
            git.log,
        )
        assertEquals(listOf("feature/ABC-1", "develop", "ABC-1 Fix the forms", "[ABC-1](https://acme.atlassian.net/browse/ABC-1)", true), ado.created.single())
        assertEquals(listOf("assign ABC-1", "transition ABC-1 31"), tracker.log)
    }

    @Test
    fun `a failed push removes the new branch and goes back`() = runBlocking {
        val git = FakeGit(failPush = true)
        val ado = FakeAdo()
        val outcome = PrWorkflow(git, ado, FakeMemory(), FakeUi()).startTicket(request(), FakeTracker())
        assertTrue(outcome is StartOutcome.Failed)
        assertEquals(listOf("switch develop", "delete feature/ABC-1"), git.log.takeLast(2))
        assertEquals("develop", git.current)
        assertFalse("feature/ABC-1" in git.local)
        assertTrue(ado.created.isEmpty())
    }

    @Test
    fun `a failed commit hook also rolls back`() = runBlocking {
        val git = FakeGit(failCommit = true)
        val outcome = PrWorkflow(git, FakeAdo(), FakeMemory(), FakeUi()).startTicket(request(), FakeTracker())
        assertTrue(outcome is StartOutcome.Failed)
        assertFalse("push feature/ABC-1" in git.log)
        assertFalse("feature/ABC-1" in git.local)
    }

    @Test
    fun `a failed PR after a successful push keeps the branch and leaves Jira alone`() = runBlocking {
        val git = FakeGit()
        val tracker = FakeTracker()
        val outcome = PrWorkflow(git, FakeAdo(failCreate = true), FakeMemory(), FakeUi()).startTicket(request(), tracker)
        assertTrue(outcome is StartOutcome.PushedWithoutPr)
        assertEquals("feature/ABC-1", git.current)
        assertTrue(tracker.log.isEmpty())
    }

    @Test
    fun `an existing local branch for the ticket is checked out instead`() = runBlocking {
        val git = FakeGit(local = mutableListOf("develop", "bugfix/ABC-1"))
        val ado = FakeAdo(openFrom = mapOf("bugfix/ABC-1" to 9))
        val outcome = PrWorkflow(git, ado, FakeMemory(), FakeUi()).startTicket(request(), FakeTracker())
        assertEquals(StartOutcome.SwitchedToExisting("bugfix/ABC-1", 9), outcome)
        assertEquals(listOf("switch bugfix/ABC-1"), git.log)
        assertTrue(ado.created.isEmpty())
    }

    @Test
    fun `a remote-only branch for the ticket is tracked`() = runBlocking {
        val git = FakeGit(remote = mutableListOf("develop", "feature/ABC-1"))
        val outcome = PrWorkflow(git, FakeAdo(), FakeMemory(), FakeUi()).startTicket(request(), FakeTracker())
        assertEquals(StartOutcome.SwitchedToExisting("feature/ABC-1", null), outcome)
        assertEquals(listOf("track feature/ABC-1"), git.log)
    }

    @Test
    fun `declining the switch does nothing`() = runBlocking {
        val git = FakeGit(local = mutableListOf("develop", "feature/ABC-1"))
        val outcome = PrWorkflow(git, FakeAdo(), FakeMemory(), FakeUi(switch = false)).startTicket(request(), FakeTracker())
        assertEquals(StartOutcome.Cancelled, outcome)
        assertTrue(git.log.isEmpty())
    }

    @Test
    fun `uncommitted changes stop the start before any branch is made`() = runBlocking {
        val git = FakeGit(uncommitted = listOf("src/a.ts"))
        val ui = FakeUi()
        val outcome = PrWorkflow(git, FakeAdo(), FakeMemory(), ui).startTicket(request(), FakeTracker())
        assertEquals(StartOutcome.Cancelled, outcome)
        assertEquals(listOf("dirty [src/a.ts]"), ui.asked)
        assertTrue(git.log.isEmpty())
    }

    @Test
    fun `Jira trouble is reported but the PR stands`() = runBlocking {
        val tracker = FakeTracker(transitions = emptyList(), failAssign = true)
        val outcome = PrWorkflow(FakeGit(), FakeAdo(), FakeMemory(), FakeUi()).startTicket(request(), tracker) as StartOutcome.Started
        assertEquals(101, outcome.prId)
        assertEquals(2, outcome.jiraProblems.size)
        assertTrue(outcome.jiraProblems[0].startsWith("Couldn't assign ABC-1"))
        assertTrue(outcome.jiraProblems[1].contains("no transition"))
    }

    @Test
    fun `a ticket already mine and in progress is left alone in Jira`() = runBlocking {
        val tracker = FakeTracker()
        PrWorkflow(FakeGit(), FakeAdo(), FakeMemory(), FakeUi()).startTicket(request(issue = issue(assignee = "me", category = "indeterminate")), tracker)
        assertTrue(tracker.log.isEmpty())
    }

    @Test
    fun `starting remembers the target and preparing fetches first`() = runBlocking {
        val git = FakeGit()
        val memory = FakeMemory()
        val workflow = PrWorkflow(git, FakeAdo(), memory, FakeUi())
        val targets = workflow.prepareStart()
        assertEquals(listOf("develop", "main", "release/1"), targets.branches)
        assertEquals("fetch", git.log.first())
        workflow.startTicket(request(), FakeTracker())
        assertEquals("develop", memory.value)
    }
}
