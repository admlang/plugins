package org.adm.intellij.ide

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import java.io.File

/**
 * Line coverage from the last `adm test --coverage-report --json` run, drawn
 * in the gutter of every open `.adm` editor: green where all generated code
 * of a line ran, yellow where some did, red where none did. Colours come
 * from the editor scheme's own coverage keys, so they follow the theme.
 */
@Service(Service.Level.PROJECT)
class ADMTestCoverage(private val project: Project) : Disposable {
	/** One file's lines by outcome, 1-based. */
	class Lines(val full: Set<Int>, val partial: Set<Int>, val none: Set<Int>)

	private enum class Kind(val key: String, val fallback: Color) {
		Full("LINE_FULL_COVERAGE", JBColor(0x4DBB5F, 0x4DBB5F)),
		Partial("LINE_PARTIAL_COVERAGE", JBColor(0xE6B35C, 0xE6B35C)),
		None("LINE_NONE_COVERAGE", JBColor(0xE05555, 0xE05555));

		fun color(): Color {
			val attrs = EditorColorsManager.getInstance().globalScheme.getAttributes(TextAttributesKey.createTextAttributesKey(key))
			return attrs?.foregroundColor ?: attrs?.backgroundColor ?: fallback
		}
	}

	/** Absolute path -> lines. */
	private val byFile = HashMap<String, Lines>()

	/** Whether the gutter shows the marks; persisted per project. */
	var visible: Boolean
		get() = PropertiesComponent.getInstance(project).getBoolean(VISIBLE_KEY, true)
		set(value) {
			PropertiesComponent.getInstance(project).setValue(VISIBLE_KEY, value, true)
			repaintAll()
		}

	init {
		EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
			override fun editorCreated(event: EditorFactoryEvent) = apply(event.editor)
		}, this)
	}

	/** Whether a run has reported anything. */
	val hasData: Boolean get() = byFile.isNotEmpty()

	/**
	 * Replaces the coverage of the given files (absolute paths); files a
	 * scoped run did not touch keep what the previous run said about them.
	 */
	fun update(files: Map<String, Lines>) {
		byFile.putAll(files)
		repaintAll()
	}

	fun clear() {
		byFile.clear()
		repaintAll()
	}

	private fun repaintAll() {
		for (editor in EditorFactory.getInstance().allEditors) {
			if (editor.project == null || editor.project == project) apply(editor)
		}
	}

	private fun apply(editor: Editor) {
		editor.getUserData(MARKS)?.forEach { runCatching { it.dispose() } }
		editor.putUserData(MARKS, null)
		val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
		val lines = byFile[File(file.path).absolutePath] ?: return
		if (!visible) return
		val model = editor.markupModel
		val lineCount = editor.document.lineCount
		val marks = ArrayList<RangeHighlighter>()
		fun mark(set: Set<Int>, kind: Kind) {
			val renderer = Renderer(kind)
			for (line in set) {
				val index = line - 1
				if (index < 0 || index >= lineCount) continue
				val h = model.addLineHighlighter(index, HighlighterLayer.ADDITIONAL_SYNTAX, null)
				h.lineMarkerRenderer = renderer
				marks.add(h)
			}
		}
		mark(lines.none, Kind.None)
		mark(lines.partial, Kind.Partial)
		mark(lines.full, Kind.Full)
		editor.putUserData(MARKS, marks)
	}

	/** A bar in the line-marker strip, the width the platform's coverage uses. */
	private class Renderer(private val kind: Kind) : LineMarkerRendererEx {
		override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
			g.color = kind.color()
			g.fillRect(r.x, r.y, JBUI.scale(4).coerceAtMost(r.width.coerceAtLeast(1)), r.height)
		}

		override fun getPosition() = LineMarkerRendererEx.Position.LEFT
	}

	override fun dispose() {
		for (editor in EditorFactory.getInstance().allEditors) {
			editor.getUserData(MARKS)?.forEach { runCatching { it.dispose() } }
			editor.putUserData(MARKS, null)
		}
		byFile.clear()
	}

	companion object {
		private val MARKS = Key.create<List<RangeHighlighter>>("ADM.Tests.coverageMarks")
		private const val VISIBLE_KEY = "ADM.Tests.showCoverage"

		fun getInstance(project: Project): ADMTestCoverage = project.service()
	}
}
