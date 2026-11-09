package se.personal.adoprreview.repo

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import com.intellij.openapi.vfs.VfsUtil

/** The few Git operations Create PR and Start Jira Ticket need. Blocking; call off the EDT. */
object GitOps {

    enum class PushState { UP_TO_DATE, NOT_PUSHED, DIFFERS }

    /**
     * Whether [branch] exists on the remote at the local commit. Asks the server (`git ls-remote`) rather than
     * trusting the local `origin/<branch>` ref, which stays behind when the remote branch is deleted (for example
     * after abandoning a PR) and would make a deleted branch look pushed.
     */
    fun pushState(project: Project, repo: ResolvedRepo, branch: String): PushState {
        val repository = repo.gitRepository
        val localHash = repository.currentRevision ?: return PushState.NOT_PUSHED
        val handler = GitLineHandler(project, repository.root, GitCommand.LS_REMOTE).apply {
            setUrls(repo.gitRemote.urls)
            addParameters(repo.gitRemote.name, "refs/heads/$branch")
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw VcsException("git ls-remote failed: ${result.errorOutputAsJoinedString}")
        val remoteHash = result.output.firstOrNull { it.isNotBlank() }?.substringBefore('\t')?.trim()
        return when {
            remoteHash.isNullOrEmpty() -> PushState.NOT_PUSHED
            remoteHash.equals(localHash, ignoreCase = true) -> PushState.UP_TO_DATE
            else -> PushState.DIFFERS
        }
    }

    /** `git push -u <remote> HEAD:refs/heads/<branch>`, then refreshes the repository state. */
    fun push(project: Project, repo: ResolvedRepo, branch: String) {
        val handler = GitLineHandler(project, repo.gitRepository.root, GitCommand.PUSH).apply {
            setUrls(repo.gitRemote.urls)
            addParameters("-u", repo.gitRemote.name, "HEAD:refs/heads/$branch")
        }
        val result = Git.getInstance().runCommand(handler)
        repo.gitRepository.update()
        if (!result.success()) throw VcsException("git push failed: ${result.errorOutputAsJoinedString}")
    }

    fun lastCommitSubject(project: Project, repo: ResolvedRepo): String? {
        val handler = GitLineHandler(project, repo.gitRepository.root, GitCommand.LOG).apply {
            addParameters("-1", "--format=%s")
        }
        return Git.getInstance().runCommand(handler).takeIf { it.success() }?.output?.firstOrNull()?.trim()
    }

    /** Tracked files with changes, staged or not (`git status --porcelain --untracked-files=no`). */
    fun uncommittedFiles(project: Project, repo: ResolvedRepo): List<String> {
        val handler = GitLineHandler(project, repo.gitRepository.root, GitCommand.STATUS).apply {
            addParameters("--porcelain", "--untracked-files=no")
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) throw VcsException("git status failed: ${result.errorOutputAsJoinedString}")
        return result.output.filter { it.isNotBlank() }.map { it.drop(3) }
    }

    /** `git fetch --prune <remote>`, so remote branches are current and deleted ones disappear. */
    fun fetch(project: Project, repo: ResolvedRepo) {
        val handler = GitLineHandler(project, repo.gitRepository.root, GitCommand.FETCH).apply {
            setUrls(repo.gitRemote.urls)
            addParameters("--prune", repo.gitRemote.name)
        }
        val result = Git.getInstance().runCommand(handler)
        repo.gitRepository.update()
        if (!result.success()) throw VcsException("git fetch failed: ${result.errorOutputAsJoinedString}")
    }

    /** `git checkout -b <branch> --no-track <startPoint>`, then refreshes the repository and the files on disk. */
    fun checkoutNewBranch(project: Project, repo: ResolvedRepo, branch: String, startPoint: String) {
        checkout(project, repo, "-b", branch, "--no-track", startPoint)
    }

    /** Switches to an existing local [branch]. */
    fun checkoutExisting(project: Project, repo: ResolvedRepo, branch: String) {
        checkout(project, repo, branch)
    }

    /** `git checkout -b <branch> --track <remote>/<branch>` for a branch that only exists on the remote. */
    fun checkoutRemote(project: Project, repo: ResolvedRepo, branch: String) {
        checkout(project, repo, "-b", branch, "--track", "${repo.gitRemote.name}/$branch")
    }

    private fun checkout(project: Project, repo: ResolvedRepo, vararg parameters: String) {
        val handler = GitLineHandler(project, repo.gitRepository.root, GitCommand.CHECKOUT).apply {
            addParameters(*parameters)
        }
        val result = Git.getInstance().runCommand(handler)
        repo.gitRepository.update()
        VfsUtil.markDirtyAndRefresh(false, true, true, repo.gitRepository.root)
        if (!result.success()) throw VcsException("git checkout failed: ${result.errorOutputAsJoinedString}")
    }

    /** `git commit --allow-empty -m <message>`; commit hooks still run. */
    fun emptyCommit(project: Project, repo: ResolvedRepo, message: String) {
        val handler = GitLineHandler(project, repo.gitRepository.root, GitCommand.COMMIT).apply {
            addParameters("--allow-empty", "-m", message)
        }
        val result = Git.getInstance().runCommand(handler)
        repo.gitRepository.update()
        if (!result.success()) throw VcsException("git commit failed: ${result.errorOutputAsJoinedString}")
    }
}
