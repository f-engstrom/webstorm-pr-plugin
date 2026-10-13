package se.personal.adoprreview.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import se.personal.adoprreview.api.dto.ChangeEntry
import se.personal.adoprreview.api.dto.ChangeKind
import se.personal.adoprreview.api.dto.CommentThread
import se.personal.adoprreview.state.LastSeen
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Changed files grouped by directory, with single-child directories compacted (`src/app/ui`).
 * Clicking a file's icon (or Space) toggles "viewed"; files changed since the last visit are marked "updated".
 */
class PrFileTree(
    private val onOpen: (files: List<ChangeEntry>, index: Int) -> Unit,
    private val onViewedChange: (change: ChangeEntry, viewed: Boolean) -> Unit,
    /** Double-click or Enter on a thread row under a file. */
    private val onOpenThread: (thread: CommentThread) -> Unit,
) : Tree() {

    /** Files in display order, which is also the order of the diff chain. */
    var files: List<ChangeEntry> = emptyList()
        private set

    /** Review threads per file path: (unresolved, total). */
    private var threadCounts: Map<String, Pair<Int, Int>> = emptyMap()

    /** Viewed files: path to the view key ([LastSeen.viewKey]) that was viewed. */
    private var viewed: Map<String, String> = emptyMap()

    /** Paths changed since the user's previous visit. */
    private var updated: Set<String> = emptySet()

    private class Directory(val name: String)

    /** A review thread shown as a child row of its file. */
    private class ThreadRow(val thread: CommentThread)

    /** Whether thread rows were added since the last [setChanges]; the first time, they are expanded. */
    private var threadRowsShown = false

    init {
        isRootVisible = false
        // Call the setters explicitly: inside a JTree subclass, Kotlin resolves `cellRenderer` and `showsRootHandles`
        // to JTree's protected fields, which bypasses the UI delegate.
        setShowsRootHandles(true)
        setCellRenderer(Renderer())
        emptyText.text = "No changed files"
        ToolTipManager.sharedInstance().registerComponent(this)
        setChanges(emptyList())

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = openSelected()
        }.installOn(this)
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && openSelected()) e.consume()
                if (e.keyCode == KeyEvent.VK_SPACE) selectedChange()?.let { toggleViewed(it); e.consume() }
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 1 || !SwingUtilities.isLeftMouseButton(e)) return
                val path = getPathForLocation(e.x, e.y) ?: return
                val change = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ChangeEntry ?: return
                val bounds = getPathBounds(path) ?: return
                if (e.x < bounds.x + JBUI.scale(20)) toggleViewed(change)
            }
        })
    }

    fun setViewState(viewed: Map<String, String>, updated: Set<String>) {
        this.viewed = viewed
        this.updated = updated
        repaint()
    }

    private fun isViewed(change: ChangeEntry) = viewed[change.path] == LastSeen.viewKey(change)

    private fun toggleViewed(change: ChangeEntry) {
        val nowViewed = !isViewed(change)
        viewed = if (nowViewed) viewed + (change.path to LastSeen.viewKey(change)) else viewed - change.path
        onViewedChange(change, nowViewed)
        repaint()
    }

    private fun selectedChange(): ChangeEntry? = (lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ChangeEntry

    fun setChanges(changes: List<ChangeEntry>) {
        threadRowsShown = false
        val root = DefaultMutableTreeNode()
        changes.forEach { insert(root, it) }
        compact(root)
        sort(root)
        model = DefaultTreeModel(root)
        files = TreeUtil.treeNodeTraverser(root).preOrderDfsTraversal()
            .mapNotNull { (it as DefaultMutableTreeNode).userObject as? ChangeEntry }
            .toList()
        TreeUtil.expandAll(this)
    }

    /** Updates per-file counts and the thread rows under each file, keeping the user's expand/collapse state. */
    fun setThreads(threads: List<CommentThread>) {
        val byPath = threads.filter { it.filePath != null }.groupBy { it.filePath!! }
        threadCounts = byPath.mapValues { (_, list) -> list.count { !it.threadStatus.isResolved } to list.size }

        val root = model.root as? DefaultMutableTreeNode ?: return
        val state = TreeState.createOn(this, root)
        val fileNodes = TreeUtil.treeNodeTraverser(root).preOrderDfsTraversal()
            .mapNotNull { it as? DefaultMutableTreeNode }
            .filter { it.userObject is ChangeEntry }
            .toList()
        for (node in fileNodes) {
            node.removeAllChildren()
            byPath[(node.userObject as ChangeEntry).path].orEmpty()
                .sortedBy { it.threadContext?.rightFileStart?.line ?: it.threadContext?.leftFileStart?.line ?: 0 }
                .forEach { node.add(DefaultMutableTreeNode(ThreadRow(it), false)) }
        }
        (model as DefaultTreeModel).nodeStructureChanged(root)
        if (threadRowsShown) state.applyTo(this) else TreeUtil.expandAll(this)
        threadRowsShown = true
    }

    private fun openSelected(): Boolean {
        ((lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ThreadRow)?.let {
            onOpenThread(it.thread)
            return true
        }
        val change = selectedChange() ?: return false
        onOpen(files, files.indexOf(change))
        return true
    }

    private fun insert(root: DefaultMutableTreeNode, change: ChangeEntry) {
        val segments = change.path.trim('/').split('/')
        var parent = root
        for (dir in segments.dropLast(1)) {
            parent = parent.children().asSequence().map { it as DefaultMutableTreeNode }
                .firstOrNull { (it.userObject as? Directory)?.name == dir }
                ?: DefaultMutableTreeNode(Directory(dir)).also(parent::add)
        }
        parent.add(DefaultMutableTreeNode(change, true))
    }

    private fun compact(node: DefaultMutableTreeNode) {
        node.children().asSequence().map { it as DefaultMutableTreeNode }.toList().forEach { child ->
            var current = child
            while (current.userObject is Directory && current.childCount == 1) {
                val only = current.getChildAt(0) as DefaultMutableTreeNode
                val onlyDir = only.userObject as? Directory ?: break
                current.userObject = Directory("${(current.userObject as Directory).name}/${onlyDir.name}")
                current.remove(only)
                only.children().asSequence().map { it as DefaultMutableTreeNode }.toList().forEach(current::add)
            }
            compact(current)
        }
    }

    private fun sort(node: DefaultMutableTreeNode) {
        val children = node.children().asSequence().map { it as DefaultMutableTreeNode }
            .sortedWith(compareBy({ it.userObject !is Directory }, { label(it).lowercase() }))
            .toList()
        node.removeAllChildren()
        children.forEach { node.add(it); sort(it) }
    }

    private fun label(node: DefaultMutableTreeNode): String = when (val value = node.userObject) {
        is Directory -> value.name
        is ChangeEntry -> value.path.substringAfterLast('/')
        else -> ""
    }

    private companion object {
        /** "Author: first line" of a thread's first comment. */
        fun summary(thread: CommentThread): String {
            val first = thread.visibleComments.firstOrNull()
            val text = first?.content.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            return "${first?.author?.displayName.orEmpty()}: ${if (text.length > 70) text.take(70) + "…" else text}"
        }

        val OPEN_THREADS = JBColor(0xB26A00, 0xE0A040)
        val UPDATED = JBColor(0x1565C0, 0x6CA6E8)
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            toolTipText = null
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is ThreadRow -> {
                    val resolved = item.thread.threadStatus.isResolved
                    icon = AllIcons.General.Balloon
                    append(summary(item.thread), if (resolved) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    val replies = item.thread.visibleComments.size - 1
                    if (replies > 0) append("  +$replies", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (resolved) append("  ${item.thread.threadStatus.label.lowercase()}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    toolTipText = "Double-click to open the diff at this comment"
                }
                is Directory -> {
                    icon = AllIcons.Nodes.Folder
                    append(item.name)
                }
                is ChangeEntry -> {
                    val fileTree = tree as? PrFileTree
                    val viewed = fileTree?.isViewed(item) == true
                    val name = item.path.substringAfterLast('/')
                    icon = if (viewed) AllIcons.Actions.Checked else FileTypeManager.getInstance().getFileTypeByFileName(name).icon
                    toolTipText = if (viewed) "Viewed. Click the icon or press Space to unmark" else "Click the icon or press Space to mark as viewed"
                    val status = when (item.kind) {
                        ChangeKind.ADD -> FileStatus.ADDED
                        ChangeKind.DELETE -> FileStatus.DELETED
                        ChangeKind.EDIT, ChangeKind.RENAME -> FileStatus.MODIFIED
                    }
                    append(name, if (viewed) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, status.color))
                    if (fileTree?.updated?.contains(item.path) == true) {
                        append("  updated", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, UPDATED))
                    }
                    if (item.kind == ChangeKind.RENAME) {
                        append("  ← ${item.basePath.trimStart('/')}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    fileTree?.threadCounts?.get(item.path)?.let { (open, total) ->
                        append("  ")
                        if (open > 0) append("$open open", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, OPEN_THREADS))
                        if (total > open) append("${if (open > 0) " · " else ""}${total - open} resolved", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
        }
    }
}
