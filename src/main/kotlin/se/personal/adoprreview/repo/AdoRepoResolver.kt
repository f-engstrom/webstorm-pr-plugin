package se.personal.adoprreview.repo

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager

data class ResolvedRepo(val remote: AdoRemote, val gitRepository: GitRepository, val gitRemote: GitRemote)

@Service(Service.Level.PROJECT)
class AdoRepoResolver(private val project: Project) : Disposable {

    /** The last answer; [NONE] until asked. Cleared when repositories or their remotes change. */
    @Volatile private var cached: Any? = NONE
    /** Bumped on every change, so a lookup that raced with a change isn't cached. */
    private val changes = java.util.concurrent.atomic.AtomicLong()

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, VcsRepositoryMappingListener { invalidate() })
        // Fires on any repository state change (commits, checkouts, remote edits); clearing is cheap.
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { invalidate() })
    }

    /**
     * The first Git repository in the project with an Azure DevOps remote, preferring `origin`. Cached, so it's cheap
     * to call from action updates and timers.
     */
    fun resolve(): ResolvedRepo? {
        val hit = cached
        if (hit !== NONE) return hit as ResolvedRepo?
        val before = changes.get()
        val found = find()
        if (changes.get() == before) cached = found
        return found
    }

    private fun invalidate() {
        changes.incrementAndGet()
        cached = NONE
    }

    private fun find(): ResolvedRepo? =
        GitRepositoryManager.getInstance(project).repositories.firstNotNullOfOrNull { repository ->
            repository.remotes
                .sortedByDescending { it.name == "origin" }
                .firstNotNullOfOrNull { gitRemote ->
                    gitRemote.urls.firstNotNullOfOrNull(AdoRemote::parse)?.let { ResolvedRepo(it, repository, gitRemote) }
                }
        }

    override fun dispose() = Unit

    companion object {
        private val NONE = Any()

        fun getInstance(project: Project): AdoRepoResolver = project.service()
    }
}
