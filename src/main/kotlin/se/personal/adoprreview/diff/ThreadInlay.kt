package se.personal.adoprreview.diff

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JPanel

object ThreadInlay {

    /** Embeds [component] as a block below 1-based [line] of [editor], for as long as [parent] lives. */
    fun add(editor: EditorEx, line: Int, component: JComponent, parent: Disposable) {
        val document = editor.document
        if (document.lineCount == 0) return
        val offset = document.getLineEndOffset((line - 1).coerceIn(0, document.lineCount - 1))
        val wrapper = EditorWidthPanel(editor, component)
        val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(
            editor,
            wrapper,
            EditorEmbeddedComponentManager.Properties(
                EditorEmbeddedComponentManager.ResizePolicy.none(),
                null,
                /* relatesToPrecedingText = */ true,
                /* showAbove = */ false,
                /* priority = */ 0,
                offset,
            ),
        ) ?: return
        Disposer.register(parent, inlay)

        // Follow the editor's width so Markdown wraps instead of running off to the right.
        val viewport = editor.scrollPane.viewport
        val listener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                wrapper.revalidate()
                // Swing listeners don't hold the write-intent lock that inlay model changes need.
                WriteIntentReadAction.run { if (inlay.isValid) inlay.update() }
            }
        }
        viewport.addComponentListener(listener)
        Disposer.register(inlay) { viewport.removeComponentListener(listener) }
    }

    private class EditorWidthPanel(private val editor: EditorEx, content: JComponent) : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 6, 0)
            add(content, BorderLayout.CENTER)
        }

        override fun getPreferredSize(): Dimension {
            // Fit inside the visible area from where the inlay starts, so it never causes horizontal scrolling.
            val visible = editor.scrollingModel.visibleArea
            val available = visible.x + visible.width - x.coerceAtLeast(0) - editor.scrollPane.verticalScrollBar.width - JBUI.scale(8)
            val width = available.coerceIn(JBUI.scale(260), JBUI.scale(900))
            return Dimension(width, super.getPreferredSize().height)
        }
    }
}
