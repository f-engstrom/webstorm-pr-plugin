package se.personal.adoprreview.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import se.personal.adoprreview.api.dto.Vote

val Vote.attributes: SimpleTextAttributes
    get() = SimpleTextAttributes(
        SimpleTextAttributes.STYLE_PLAIN,
        when (this) {
            Vote.APPROVED, Vote.APPROVED_WITH_SUGGESTIONS -> JBColor(0x2E7D32, 0x6CBF6C)
            Vote.WAITING_FOR_AUTHOR -> JBColor(0xB26A00, 0xE0A040)
            Vote.REJECTED -> JBColor(0xC62828, 0xE06C6C)
            Vote.NONE -> JBColor.GRAY
        },
    )
