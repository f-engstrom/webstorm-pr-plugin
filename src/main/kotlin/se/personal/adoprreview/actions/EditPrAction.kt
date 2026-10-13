package se.personal.adoprreview.actions

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import se.personal.adoprreview.AdoNotifications
import se.personal.adoprreview.api.AdoClient
import se.personal.adoprreview.api.dto.PullRequestUpdate
import se.personal.adoprreview.repo.AdoRemote
import se.personal.adoprreview.state.PrReviewService
import se.personal.adoprreview.toolwindow.EditPrDialog

/** Edit and Publish in the details header. [onDone] reloads the list and details. */
object EditPrAction {

    fun edit(project: Project, remote: AdoRemote, prId: Int, onDone: () -> Unit) = run(project, "Couldn't update #$prId") {
        // Fetch the raw PR: the details panel shows the description with @mentions already replaced by names.
        val pr = withBackgroundProgress(project, "Loading #$prId") { AdoClient.getInstance().pullRequest(remote, prId) }
        val dialog = EditPrDialog(project, prId, pr.title, pr.description.orEmpty(), pr.isDraft)
        if (!dialog.showAndGet()) return@run
        val update = PullRequestUpdate(
            title = dialog.prTitle.takeIf { it != pr.title },
            description = dialog.prDescription.takeIf { it != pr.description.orEmpty() },
            isDraft = dialog.isDraft.takeIf { it != pr.isDraft },
        )
        if (update == PullRequestUpdate()) return@run
        withBackgroundProgress(project, "Updating #$prId") { AdoClient.getInstance().updatePullRequest(remote, prId, update) }
        onDone()
    }

    fun publish(project: Project, remote: AdoRemote, prId: Int, onDone: () -> Unit) = run(project, "Couldn't publish #$prId") {
        withBackgroundProgress(project, "Publishing #$prId") {
            AdoClient.getInstance().updatePullRequest(remote, prId, PullRequestUpdate(isDraft = false))
        }
        AdoNotifications.info(project, "Pull request published", "#$prId is no longer a draft; reviewers are notified.", remote.webUrl(prId))
        onDone()
    }

    private fun run(project: Project, errorTitle: String, block: suspend () -> Unit) {
        PrReviewService.getInstance(project).scope.launch(Dispatchers.EDT) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AdoNotifications.error(project, errorTitle, e)
            }
        }
    }
}
