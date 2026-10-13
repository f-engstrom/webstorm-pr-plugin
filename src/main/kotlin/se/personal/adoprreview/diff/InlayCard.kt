package se.personal.adoprreview.diff

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

/**
 * A rounded card with a coloured stripe on its left edge, so inline comment boxes stand out from the code around
 * them (the editor-tab background they used before is the same as the editor's in dark themes).
 */
open class InlayCard(layout: LayoutManager, accent: Color) : JPanel(layout) {

    var accent: Color = accent
        set(value) {
            field = value
            repaint()
        }

    init {
        isOpaque = false
        // Leaves room for the stripe on the left.
        border = JBUI.Borders.empty(8, 16, 8, 12)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(12).toFloat()
            val card = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc)
            g2.color = BACKGROUND
            g2.fill(card)
            g2.clip(card)
            g2.color = accent
            g2.fillRect(0, 0, JBUI.scale(4), height)
            g2.clip = null
            g2.color = BORDER
            g2.draw(card)
        } finally {
            g2.dispose()
        }
    }

    companion object {
        /** A step away from the editor background in both light and dark themes. */
        val BACKGROUND = JBColor(0xF0F2F6, 0x2B2D30)
        val BORDER = JBColor(0xC5C9D2, 0x4A4D52)

        val ACTIVE = JBColor(0xD9822B, 0xE0A040)
        val RESOLVED = JBColor(0x9AA0A6, 0x6F737A)
        val NEW = JBColor(0x3574F0, 0x548AF7)
    }
}
