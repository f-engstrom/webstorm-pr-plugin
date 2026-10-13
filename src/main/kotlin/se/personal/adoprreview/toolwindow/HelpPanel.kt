package se.personal.adoprreview.toolwindow

import com.intellij.ui.ScrollPaneFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

/** The Help tab: `help/help.md` rendered as Markdown. */
class HelpPanel : JPanel(BorderLayout()) {
    init {
        val markdown = HelpPanel::class.java.getResource("/help/help.md")?.readText() ?: "Help is missing from the plugin."
        val pane = Markdown.createPane().apply {
            border = JBUI.Borders.empty(8, 12)
            text = Markdown.toHtml(markdown)
            caretPosition = 0
        }
        add(ScrollPaneFactory.createScrollPane(pane, true), BorderLayout.CENTER)
    }
}
