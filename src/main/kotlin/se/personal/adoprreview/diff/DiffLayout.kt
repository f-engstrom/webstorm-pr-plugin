package se.personal.adoprreview.diff

import com.intellij.diff.FrameDiffTool
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.openapi.editor.ex.EditorEx

/**
 * Maps PR file lines (per side, 0-based) onto a diff viewer's editors and back. Side-by-side viewers have an editor
 * per side; one-sided viewers (added/deleted files) only their own side; the unified viewer (also used by the
 * all-files view) interleaves both sides in one editor.
 */
sealed class DiffLayout {

    abstract val editors: List<EditorEx>

    /** The editor and its 0-based line that shows [line] of [side], or null if this viewer doesn't show it. */
    abstract fun toEditor(side: Side, line: Int): Pair<EditorEx, Int>?

    /** The side and its 0-based line shown at [editorLine] of [editor], or null (e.g. past the end). */
    abstract fun fromEditor(editor: EditorEx, editorLine: Int): Pair<Side, Int>?

    /** The editor to put banners about [side] on. */
    abstract fun bannerEditor(side: Side): EditorEx?

    /** Whether lines can be mapped right now. The unified viewer only can after its first diff. */
    open val isReady: Boolean get() = true

    /** Calls [action] after every rediff, for layouts whose editor content is rebuilt then. */
    open fun onRediff(action: () -> Unit) {}

    /** The viewer, for [onDispose]. */
    protected abstract val base: DiffViewerBase

    /**
     * Calls [action] when the viewer is disposed. Use this rather than registering children on the viewer with
     * Disposer: the all-files view disposes block viewers directly when they scroll out, bypassing Disposer.
     */
    fun onDispose(action: () -> Unit) {
        base.addListener(object : DiffViewerListener() {
            override fun onDispose() = action()
        })
    }

    private class Twoside(private val viewer: TwosideTextDiffViewer) : DiffLayout() {
        override val base get() = viewer
        override val editors get() = viewer.editors
        override fun toEditor(side: Side, line: Int) = viewer.getEditor(side).takeIf { line < it.document.lineCount }?.let { it to line }
        override fun fromEditor(editor: EditorEx, editorLine: Int): Pair<Side, Int>? {
            val side = Side.entries.firstOrNull { viewer.getEditor(it) == editor } ?: return null
            return (side to editorLine).takeIf { editorLine < editor.document.lineCount }
        }
        override fun bannerEditor(side: Side): EditorEx = viewer.getEditor(side)
    }

    private class Oneside(private val viewer: OnesideTextDiffViewer) : DiffLayout() {
        override val base get() = viewer
        override val editors get() = listOf(viewer.editor)
        override fun toEditor(side: Side, line: Int) =
            viewer.editor.takeIf { side == viewer.side && line < it.document.lineCount }?.let { it to line }
        override fun fromEditor(editor: EditorEx, editorLine: Int) =
            (viewer.side to editorLine).takeIf { editor == viewer.editor && editorLine < editor.document.lineCount }
        override fun bannerEditor(side: Side): EditorEx? = viewer.editor.takeIf { side == viewer.side }
    }

    private class Unified(private val viewer: UnifiedDiffViewer) : DiffLayout() {
        override val base get() = viewer
        override val editors get() = listOf(viewer.editor)
        override val isReady get() = viewer.isContentGood
        override fun toEditor(side: Side, line: Int) =
            viewer.transferLineToOnesideStrict(side, line).takeIf { it >= 0 }?.let { viewer.editor to it }

        /** Unchanged and inserted lines count as the new (right) side; removed lines as the old (left) side. */
        override fun fromEditor(editor: EditorEx, editorLine: Int): Pair<Side, Int>? {
            if (editor != viewer.editor) return null
            return sequenceOf(Side.RIGHT, Side.LEFT)
                .map { it to viewer.transferLineFromOnesideStrict(it, editorLine) }
                .firstOrNull { it.second >= 0 }
        }

        override fun bannerEditor(side: Side): EditorEx = viewer.editor

        override fun onRediff(action: () -> Unit) {
            viewer.addListener(object : DiffViewerListener() {
                override fun onAfterRediff() = action()
            })
        }
    }

    companion object {
        fun of(viewer: FrameDiffTool.DiffViewer): DiffLayout? = when (viewer) {
            is TwosideTextDiffViewer -> Twoside(viewer)
            is OnesideTextDiffViewer -> Oneside(viewer)
            is UnifiedDiffViewer -> Unified(viewer)
            else -> null
        }
    }
}
