package se.personal.adoprreview.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import se.personal.adoprreview.api.dto.PullRequest
import se.personal.adoprreview.api.dto.Vote
import se.personal.adoprreview.state.Badge
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/** Two-line row: badges and title on top; `#id · author → target · my vote` below. */
class PrCellRenderer : ListCellRenderer<PullRequest> {

    var myId: String? = null
    var badges: Map<Int, Set<Badge>> = emptyMap()

    private val title = SimpleColoredComponent().apply { isOpaque = false; ipad = JBUI.emptyInsets() }
    private val info = SimpleColoredComponent().apply { isOpaque = false; ipad = JBUI.emptyInsets() }
    private val panel = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
        border = JBUI.Borders.empty(5, 8)
        add(title, BorderLayout.NORTH)
        add(info, BorderLayout.SOUTH)
    }

    override fun getListCellRendererComponent(
        list: JList<out PullRequest>, pr: PullRequest, index: Int, selected: Boolean, focused: Boolean,
    ): Component {
        panel.background = UIUtil.getListBackground(selected, focused)
        val foreground = UIUtil.getListForeground(selected, focused)
        title.foreground = foreground
        info.foreground = foreground
        val gray = if (selected) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES

        title.clear()
        badges[pr.pullRequestId].orEmpty().sortedBy { it.ordinal }.forEach { badge ->
            title.append("${badge.label}  ", if (selected) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else BADGE)
        }
        if (pr.isDraft) title.append("Draft  ", if (selected) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else DRAFT)
        title.append(pr.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)

        info.clear()
        info.append("#${pr.pullRequestId}  ·  ${pr.createdBy.displayName}  →  ${pr.targetBranch}", gray)
        val vote = Vote.of(pr.voteOf(myId))
        if (vote != Vote.NONE) {
            info.append("  ·  ", gray)
            info.append(vote.label, if (selected) SimpleTextAttributes.REGULAR_ATTRIBUTES else vote.attributes)
        }
        return panel
    }

    private companion object {
        val DRAFT = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.GRAY)
        val BADGE = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0x1565C0, 0x6CA6E8))
    }
}
