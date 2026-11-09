package se.personal.adoprreview.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import se.personal.adoprreview.api.dto.Vote
import se.personal.adoprreview.repo.AdoRemote
import se.personal.adoprreview.state.PrReviewService
import java.awt.Point
import javax.swing.JButton

/** "Vote: Approved ▾" button in the details panel; picking an option sets my vote on the PR. */
class VoteAction(private val project: Project, private val onVoted: () -> Unit) : JButton() {

    private var remote: AdoRemote? = null
    private var prId = 0
    private var current = Vote.NONE

    init {
        icon = AllIcons.General.ArrowDown
        horizontalTextPosition = LEADING
        addActionListener { showPopup() }
    }

    fun bind(remote: AdoRemote, prId: Int, current: Vote) {
        this.remote = remote
        this.prId = prId
        this.current = current
        text = "Vote: ${current.label}"
        isEnabled = true
    }

    private fun showPopup() {
        val choices = Vote.entries.filter { it != current }
        JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setRenderer(textListCellRenderer { it?.action })
            .setItemChosenCallback(::vote)
            .createPopup()
            .show(RelativePoint(this, Point(0, height)))
    }

    private fun vote(vote: Vote) {
        val remote = remote ?: return
        val prId = prId
        isEnabled = false
        val service = PrReviewService.getInstance(project)
        service.launchOperation("Couldn't vote on #$prId", onFailure = { isEnabled = true }) {
            service.vote(remote, prId, vote)
            onVoted()
        }
    }
}
