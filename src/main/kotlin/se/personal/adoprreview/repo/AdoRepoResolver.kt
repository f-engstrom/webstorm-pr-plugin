package se.personal.adoprreview.repo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

data class ResolvedRepo(val remote: AdoRemote, val gitRepository: GitRepository, val gitRemote: GitRemote)

@Service(Service.Level.PROJECT)
class AdoRepoResolver(private val project: Project) {

    /** The first Git repository in the project with an Azure DevOps remote, preferring `origin`. */
    fun resolve(): ResolvedRepo? =
        GitRepositoryManager.getInstance(project).repositories.firstNotNullOfOrNull { repository ->
            repository.remotes
                .sortedByDescending { it.name == "origin" }
                .firstNotNullOfOrNull { gitRemote ->
                    gitRemote.urls.firstNotNullOfOrNull(AdoRemote::parse)?.let { ResolvedRepo(it, repository, gitRemote) }
                }
        }

    companion object {
        fun getInstance(project: Project): AdoRepoResolver = project.service()
    }
}
