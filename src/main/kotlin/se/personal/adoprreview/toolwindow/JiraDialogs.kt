package se.personal.adoprreview.toolwindow

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.personal.adoprreview.jira.JiraAuth
import se.personal.adoprreview.jira.JiraIssue
import se.personal.adoprreview.jira.TicketBranches
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** Asks for the Jira site, email and API token. [error] shows why the previous attempt failed. */
class JiraLoginDialog(project: Project, site: String?, email: String?, private val error: String?) : DialogWrapper(project) {

    private val siteField = JBTextField(site.orEmpty())
    private val emailField = JBTextField(email.orEmpty())
    private val tokenField = JBPasswordField()

    val site: String get() = JiraAuth.normalize(siteField.text)
    val login: JiraAuth.Login get() = JiraAuth.Login(emailField.text.trim(), String(tokenField.password).trim())

    init {
        title = "Sign In to Jira"
        setOKButtonText("Sign In")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        if (error != null) row { label(error).applyToComponent { foreground = JBUI.CurrentTheme.Validator.errorBorderColor() } }
        row("Jira site:") { cell(siteField).align(AlignX.FILL).comment("e.g. https://your-company.atlassian.net. Remembered for this Azure DevOps org.") }
        row("Email:") { cell(emailField).align(AlignX.FILL) }
        row("API token:") {
            cell(tokenField).align(AlignX.FILL)
                .comment("Create one at <a href='https://id.atlassian.com/manage-profile/security/api-tokens'>id.atlassian.com → Security → API tokens</a>. Stored in the macOS Keychain.")
        }
    }.apply { preferredSize = JBUI.size(520, preferredSize.height) }

    override fun getPreferredFocusedComponent(): JComponent = when {
        siteField.text.isBlank() -> siteField
        emailField.text.isBlank() -> emailField
        else -> tokenField
    }

    override fun doValidate(): ValidationInfo? = when {
        siteField.text.isBlank() -> ValidationInfo("Enter the Jira site", siteField)
        emailField.text.isBlank() -> ValidationInfo("Enter your Atlassian email", emailField)
        tokenField.password.isEmpty() -> ValidationInfo("Enter an API token", tokenField)
        else -> null
    }
}

/**
 * Pick a ticket and how to branch for it. Lists [issues]; typing a key that isn't listed (e.g. someone else's ticket)
 * looks it up with [lookup].
 */
class StartTicketDialog(
    project: Project,
    issues: List<JiraIssue>,
    targetBranches: List<String>,
    defaultBranch: String?,
    defaultTarget: String?,
    assign: Boolean,
    moveToInProgress: Boolean,
    private val scope: CoroutineScope,
    private val lookup: suspend (String) -> JiraIssue?,
) : PrDialog(project) {

    private val all = issues.toMutableList()
    private val model = CollectionListModel(issues)
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 12
        cellRenderer = object : ColoredListCellRenderer<JiraIssue>() {
            override fun customizeCellRenderer(list: JList<out JiraIssue>, value: JiraIssue, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(value.key, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ")
                append(value.summary)
                val extra = listOfNotNull(value.issueType, value.statusName).joinToString(" · ")
                if (extra.isNotEmpty()) append("  $extra", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        emptyText.text = "No unresolved tickets assigned to you. Type a key, e.g. ABC-123."
    }
    private val search = SearchTextField(false)
    private val prefix = ComboBox(TicketBranches.PREFIXES.toTypedArray()).apply { isEditable = true }
    private val branchPreview = JBLabel()
    private val target = BranchPicker(targetBranches, defaultBranch).apply {
        text = defaultTarget ?: targetBranches.firstOrNull().orEmpty()
    }
    private val assignBox = JBCheckBox("Assign to me", assign)
    private val progressBox = JBCheckBox("Move to In Progress", moveToInProgress)
    private var lookupJob: Job? = null

    val issue: JiraIssue? get() = list.selectedValue
    val branchName: String get() = issue?.let { TicketBranches.branchName(prefixText, it.key) }.orEmpty()
    val targetBranch: String get() = target.text.trim()
    val assignToMe: Boolean get() = assignBox.isSelected
    val moveToInProgress: Boolean get() = progressBox.isSelected

    private val prefixText: String get() = (prefix.editor.item as? String ?: prefix.selectedItem as? String).orEmpty()

    init {
        title = "Start Jira Ticket"
        setOKButtonText("Start")
        list.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            issue?.let { selected -> prefix.selectedItem = TicketBranches.prefixFor(selected.issueType) }
            updatePreview()
            refreshValidation()
        }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })
        // ↓ and ↑ in the search field move through the list, so you can filter and pick without the mouse.
        search.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val delta = when (e.keyCode) {
                    KeyEvent.VK_DOWN -> 1
                    KeyEvent.VK_UP -> -1
                    else -> return
                }
                if (model.size == 0) return
                e.consume()
                val next = (list.selectedIndex + delta).coerceIn(0, model.size - 1)
                list.selectedIndex = next
                list.ensureIndexIsVisible(next)
            }
        })
        (prefix.editor.editorComponent as? javax.swing.text.JTextComponent)?.document?.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updatePreview()
        })
        prefix.addActionListener { updatePreview() }
        target.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refreshValidation()
        })
        if (model.size > 0) list.selectedIndex = 0
        updatePreview()
        init()
    }

    private fun applyFilter() {
        val query = search.text
        val selected = issue
        model.replaceAll(TicketBranches.filter(all, query))
        val keep = selected?.let { model.items.indexOf(it) } ?: -1
        list.selectedIndex = if (keep >= 0) keep else if (model.size > 0) 0 else -1
        updatePreview()

        lookupJob?.cancel()
        val key = TicketBranches.asKey(query) ?: return
        if (all.any { it.key == key }) return
        list.emptyText.text = "Looking up $key…"
        lookupJob = scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            delay(300)
            val found = runCatching { lookup(key) }.getOrNull()
            if (TicketBranches.asKey(search.text) != key) return@launch
            if (found == null) {
                list.emptyText.text = "No ticket $key, or you can't see it."
                return@launch
            }
            all.add(0, found)
            model.replaceAll(listOf(found))
            list.selectedIndex = 0
        }
    }

    private fun updatePreview() {
        branchPreview.text = branchName.ifEmpty { "Pick a ticket" }
    }

    override fun createCenterPanel(): JComponent = panel {
        row { cell(search).align(AlignX.FILL).comment("Filter by key or words in the summary, or type any ticket key.") }
        row { cell(ScrollPaneFactory.createScrollPane(list)).align(Align.FILL) }.resizableRow()
        row("Prefix:") { cell(prefix) }
        row("Branch:") { cell(branchPreview) }
        row("From and into:") {
            cell(target).align(AlignX.FILL)
                .comment("The branch starts at the latest remote commit of this branch, and the draft PR targets it.")
        }
        row { cell(assignBox); cell(progressBox) }
    }.apply { preferredSize = JBUI.size(640, 520) }

    override fun getPreferredFocusedComponent(): JComponent = search.textEditor

    override fun doValidate(): ValidationInfo? = when {
        issue == null -> ValidationInfo("Pick a ticket", search.textEditor)
        targetBranch.isEmpty() -> ValidationInfo("Pick the branch to start from", target.textField)
        else -> null
    }

    override fun dispose() {
        lookupJob?.cancel()
        super.dispose()
    }
}
