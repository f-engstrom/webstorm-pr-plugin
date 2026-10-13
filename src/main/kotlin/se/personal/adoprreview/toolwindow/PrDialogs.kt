package se.personal.adoprreview.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import se.personal.adoprreview.state.PrTemplates
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/** Title, description and draft fields shared by both dialogs. */
private class PrFields(initialTitle: String, initialDescription: String, initiallyDraft: Boolean) {
    val title = JBTextField(initialTitle)
    val description = JBTextArea(initialDescription, 10, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4)
    }
    val draft = JBCheckBox("Draft", initiallyDraft)
    val descriptionScroll = JBScrollPane(description)
}

class CreatePrDialog(
    project: Project,
    private val sourceBranch: String,
    targetBranches: List<String>,
    defaultTarget: String?,
    title: String,
    jiraSite: String?,
    draft: Boolean,
) : DialogWrapper(project) {

    private val target = ComboBox(targetBranches.toTypedArray()).apply {
        isEditable = true
        selectedItem = defaultTarget ?: targetBranches.firstOrNull()
    }
    private val jiraSiteField = JBTextField(jiraSite.orEmpty())
    private val fields = PrFields(title, PrTemplates.description(sourceBranch, jiraSite), draft)

    /** The description still holds the generated ticket link (not edited), so it may follow Jira site changes. */
    private var generatedDescription = fields.description.text

    val targetBranch: String get() = (target.editor.item as? String ?: target.selectedItem as? String).orEmpty().trim()
    val prTitle: String get() = fields.title.text.trim()
    val prDescription: String get() = fields.description.text
    val isDraft: Boolean get() = fields.draft.isSelected
    val jiraSite: String get() = jiraSiteField.text.trim()

    init {
        this.title = "Create Pull Request"
        setOKButtonText("Create")
        jiraSiteField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (fields.description.text != generatedDescription) return
                generatedDescription = PrTemplates.description(sourceBranch, jiraSiteField.text)
                fields.description.text = generatedDescription
            }
        })
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("From:") { label(sourceBranch) }
        row("Into:") { cell(target).align(AlignX.FILL) }
        row("Title:") { cell(fields.title).align(AlignX.FILL).focused() }
        row("Description:") {}
        row { cell(fields.descriptionScroll).align(Align.FILL) }.resizableRow()
        row { cell(fields.draft) }
        row("Jira site:") {
            cell(jiraSiteField).align(AlignX.FILL)
                .comment("For the ticket link, e.g. https://your-company.atlassian.net. Remembered for this Azure DevOps org.")
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = fields.title

    override fun doValidate(): ValidationInfo? = when {
        prTitle.isEmpty() -> ValidationInfo("Enter a title", fields.title)
        PrTemplates.ticketKey(sourceBranch)?.let { prTitle == it } == true -> ValidationInfo("Add the summary after the ticket key", fields.title)
        targetBranch.isEmpty() -> ValidationInfo("Pick a target branch", target)
        targetBranch == sourceBranch -> ValidationInfo("Target must differ from the source branch", target)
        else -> null
    }
}

class EditPrDialog(project: Project, prId: Int, title: String, description: String, draft: Boolean) : DialogWrapper(project) {

    private val fields = PrFields(title, description, draft)

    val prTitle: String get() = fields.title.text.trim()
    val prDescription: String get() = fields.description.text
    val isDraft: Boolean get() = fields.draft.isSelected

    init {
        this.title = "Edit Pull Request #$prId"
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Title:") { cell(fields.title).align(AlignX.FILL).focused() }
        row("Description:") {}
        row { cell(fields.descriptionScroll).align(Align.FILL) }.resizableRow()
        row { cell(fields.draft) }
    }

    override fun getPreferredFocusedComponent(): JComponent = fields.title

    override fun doValidate(): ValidationInfo? =
        if (prTitle.isEmpty()) ValidationInfo("Enter a title", fields.title) else null
}
