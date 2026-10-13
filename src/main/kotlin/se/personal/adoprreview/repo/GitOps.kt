package se.personal.adoprreview.repo

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler

/** The few Git operations Create PR needs. Blocking; call off the EDT. */
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
}
