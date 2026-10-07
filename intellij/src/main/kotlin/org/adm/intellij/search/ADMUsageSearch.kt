package org.adm.intellij.search

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector.Access
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.usageView.UsageInfo
import com.intellij.usages.ReadWriteAccessUsageInfo2UsageAdapter
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation
import org.adm.intellij.lang.ADMIdentifiers
import org.adm.intellij.lsp.ADMReferences
import javax.swing.JList

/**
 * Shows the usages of the ADM symbol at an offset: a popup list to jump from,
 * with a last row that opens them all in the Find tool window. Call on the
 * EDT.
 *
 * ADM has no named PSI elements to hang the platform's usage search on, so the
 * usages come from the language server (`textDocument/references`) and are
 * shown through the public usage-view classes. The platform's own Show Usages
 * popup takes a search target only through internal API.
 */
object ADMUsageSearch {

	/** A row of the popup: one usage, or (with no usage) the row that opens the Find tool window. */
	private class Row(val usage: Usage?, val place: String, val text: String, val access: String?)

	fun show(project: Project, editor: Editor, file: VirtualFile, offset: Int, at: RelativePoint? = null) {
		val name = ADMIdentifiers.at(editor.document, offset) ?: return
		val site = ADMReferences.siteAt(project, editor, file, offset)
		val point = at ?: JBPopupFactory.getInstance().guessBestPopupLocation(editor)

		object : Task.Backgroundable(project, "Finding usages of '$name'", true) {
			private var result: ADMReferences.Result = ADMReferences.Result.Empty
			private var rows: List<Row> = emptyList()

			override fun run(indicator: ProgressIndicator) {
				val found = ADMReferences.at(project, site)
				result = found
				if (found is ADMReferences.Result.Found) {
					rows = ReadAction.compute<List<Row>, RuntimeException> { rowsOf(project, found.refs) }
				}
			}

			override fun onSuccess() {
				when (val outcome = result) {
					ADMReferences.Result.NoServer -> tell(point, "The ADM language server is not running", MessageType.WARNING)
					is ADMReferences.Result.Failed -> tell(point, "Usage search failed: ${outcome.reason}", MessageType.WARNING)
					else -> if (rows.isEmpty()) tell(point, "No usages of '$name'", MessageType.INFO) else list(project, name, rows, point)
				}
			}
		}.queue()
	}

	private fun rowsOf(project: Project, refs: List<ADMReferences.Ref>): List<Row> {
		val psiManager = PsiManager.getInstance(project)
		val documents = FileDocumentManager.getInstance()
		val base = project.guessProjectDir()
		return refs.mapNotNull { ref ->
			val file = ref.file ?: return@mapNotNull null
			val psiFile = psiManager.findFile(file) ?: return@mapNotNull null
			val document = documents.getDocument(file) ?: return@mapNotNull null
			val start = offsetOf(document, ref.line, ref.column) ?: return@mapNotNull null
			val end = maxOf(start, offsetOf(document, ref.endLine, ref.endColumn) ?: start)
			val info = UsageInfo(psiFile, start, end)
			val usage: Usage = when (ref.access) {
				"read" -> ReadWriteAccessUsageInfo2UsageAdapter(info, Access.Read)
				"write" -> ReadWriteAccessUsageInfo2UsageAdapter(info, Access.Write)
				"readwrite" -> ReadWriteAccessUsageInfo2UsageAdapter(info, Access.ReadWrite)
				else -> UsageInfo2UsageAdapter(info)
			}
			val path = base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name
			val line = document.getText(TextRange(document.getLineStartOffset(ref.line), document.getLineEndOffset(ref.line))).trim()
			Row(usage, "$path:${ref.line + 1}", line, ref.access)
		}
	}

	private fun offsetOf(document: Document, line: Int, character: Int): Int? {
		if (line >= document.lineCount) return null
		val offset = document.getLineStartOffset(line) + character
		return offset.takeIf { it <= document.textLength }
	}

	private fun list(project: Project, name: String, rows: List<Row>, point: RelativePoint) {
		val usages = rows.mapNotNull { it.usage }
		val all = rows + Row(null, "", "Open in Find tool window", null)
		JBPopupFactory.getInstance()
			.createPopupChooserBuilder(all)
			.setTitle(if (usages.size == 1) "1 usage of '$name'" else "${usages.size} usages of '$name'")
			.setRenderer(object : ColoredListCellRenderer<Row>() {
				override fun customizeCellRenderer(list: JList<out Row>, value: Row, index: Int, selected: Boolean, hasFocus: Boolean) {
					if (value.usage == null) {
						append(value.text, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES)
						return
					}
					append(value.place, SimpleTextAttributes.GRAYED_ATTRIBUTES)
					append("  ")
					append(value.text)
					value.access?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
				}
			})
			.setNamerForFiltering { "${it.place} ${it.text}" }
			.setItemChosenCallback { row ->
				val usage = row.usage
				if (usage != null) usage.navigate(true) else findWindow(project, name, usages)
			}
			.createPopup()
			.show(point)
	}

	private fun findWindow(project: Project, name: String, usages: List<Usage>) {
		val presentation = UsageViewPresentation().apply {
			tabText = "Usages of $name"
			searchString = "usages of '$name'"
			targetsNodeText = name
			codeUsagesString = "Usages of $name"
		}
		UsageViewManager.getInstance(project).showUsages(UsageTarget.EMPTY_ARRAY, usages.toTypedArray(), presentation)
	}

	private fun tell(point: RelativePoint, text: String, type: MessageType) {
		JBPopupFactory.getInstance()
			.createHtmlTextBalloonBuilder(text, type, null)
			.setFadeoutTime(4000)
			.createBalloon()
			.show(point, Balloon.Position.above)
	}
}
