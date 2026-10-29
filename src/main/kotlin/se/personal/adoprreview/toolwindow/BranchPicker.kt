package se.personal.adoprreview.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.text.NaturalComparator
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.Icon

/**
 * A target branch field: type a name, or open a list grouped by folder (`feature/…`) or numbered family (`dev01`,
 * `dev02` …) and type to filter it.
 */
class BranchPicker(private val branches: List<String>, defaultBranch: String?) : TextFieldWithBrowseButton() {

    private val grouped = group(branches, defaultBranch)

    init {
        addActionListener { showPopup() }
        textField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_DOWN || (e.keyCode == KeyEvent.VK_SPACE && e.isControlDown)) {
                    e.consume()
                    showPopup()
                }
            }
        })
    }

    private fun showPopup() {
        val separators = grouped.associate { it.branches.first() to it.title }
        val step = object : BaseListPopupStep<String>(null, grouped.flatMap { it.branches }) {
            override fun getTextFor(value: String) = value
            override fun getIconFor(value: String): Icon = AllIcons.Vcs.Branch
            override fun getSeparatorAbove(value: String) = separators[value]?.let(::ListSeparator)
            override fun isSpeedSearchEnabled() = true
            override fun getDefaultOptionIndex() = values.indexOf(text.trim()).coerceAtLeast(0)
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                text = selectedValue
                return FINAL_CHOICE
            }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(step, 20)
        popup.setMinimumSize(java.awt.Dimension(width, 0))
        popup.showUnderneathOf(this)
    }

    private class Group(val title: String, val branches: List<String>)

    private companion object {
        val NUMBERED = Regex("""^(.*?\D)\d+$""")

        fun isMainLike(name: String) = name.startsWith("develop") || name == "main" || name == "master"

        /** Suggested first, then folders alphabetically, then numbered families, then everything else. */
        fun group(branches: List<String>, defaultBranch: String?): List<Group> {
            val suggested = branches.filter { it == defaultBranch || isMainLike(it) }
                .sortedWith(compareBy({ it != defaultBranch }, { it }))
            val rest = branches - suggested.toSet()

            val folders = rest.filter { '/' in it }.groupBy { it.substringBefore('/') }
            val flat = rest.filter { '/' !in it }
            val families = flat.groupBy { NUMBERED.find(it)?.groupValues?.get(1) }
                .filterKeys { it != null }
                .filterValues { it.size > 1 }
            val other = flat - families.values.flatten().toSet()

            return buildList {
                if (suggested.isNotEmpty()) add(Group("Suggested", suggested))
                folders.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    .forEach { (folder, names) -> add(Group("$folder/", names.sortedWith(NaturalComparator.INSTANCE))) }
                families.toSortedMap(compareBy { it!!.lowercase() })
                    .forEach { (stem, names) -> add(Group("$stem…", names.sortedWith(NaturalComparator.INSTANCE))) }
                if (other.isNotEmpty()) add(Group("Other", other.sortedWith(NaturalComparator.INSTANCE)))
            }
        }
    }
}
