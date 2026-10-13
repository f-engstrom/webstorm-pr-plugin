package se.personal.adoprreview.toolwindow

import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import javax.swing.JEditorPane

object Markdown {
    private val flavour = GFMFlavourDescriptor()

    fun toHtml(markdown: String): String =
        HtmlGenerator(
            markdown,
            MarkdownParser(flavour, cancellationToken = CancellationToken.NonCancellable).buildMarkdownTreeFromString(markdown as CharSequence),
            flavour,
        ).generateHtml()

    /** A read-only, wrapping HTML pane styled like the IDE, with links opening in the browser. */
    fun createPane(): JEditorPane = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
    }
}
