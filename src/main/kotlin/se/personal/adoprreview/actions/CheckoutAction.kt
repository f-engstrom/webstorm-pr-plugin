package se.personal.adoprreview.actions

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import git4idea.branch.GitBrancher
import git4idea.fetch.GitFetchSupport
import se.personal.adoprreview.api.dto.PullRequest
import se.personal.adoprreview.repo.ResolvedRepo

/** Fetches the PR's source branch and checks it out as a local branch tracking the remote one. */
object CheckoutAction {

    fun checkout(project: Project, repo: ResolvedRepo, pr: PullRequest) {
        val branch = pr.sourceBranch
        val remote = repo.gitRemote
        val remoteBranch = "${remote.name}/$branch"
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Fetching $remoteBranch", true) {
            private var fetched = false

            override fun run(indicator: ProgressIndicator) {
                val result = GitFetchSupport.fetchSupport(project)
                    .fetch(repo.gitRepository, remote, "+refs/heads/$branch:refs/remotes/$remoteBranch")
                fetched = !result.showNotificationIfFailed("Couldn't fetch $remoteBranch")
            }

            override fun onSuccess() {
                if (!fetched) return
                val brancher = GitBrancher.getInstance(project)
                val repositories = listOf(repo.gitRepository)
                if (repo.gitRepository.branches.findLocalBranch(branch) != null) {
                    // Existing local branch: switch to it; pulling is left to the user.
                    brancher.checkout(branch, false, repositories, null)
                } else {
                    // `checkout -b branch origin/branch` also sets up tracking.
                    brancher.checkoutNewBranchStartingFrom(branch, remoteBranch, repositories, null)
                }
            }
        })
    }
}
