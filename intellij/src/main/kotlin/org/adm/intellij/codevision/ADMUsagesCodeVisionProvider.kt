package org.adm.intellij.codevision

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.ui.awt.RelativePoint
import org.adm.intellij.lang.ADMFileType
import org.adm.intellij.lsp.ADMCodeLenses
import org.adm.intellij.lsp.ADMServerRefresh
import org.adm.intellij.lsp.ADMReferences
import org.adm.intellij.search.ADMUsageSearch

/**
 * The "N usages" hint above ADM declarations.
 *
 * The platform's own usages code vision resolves references through PSI, and
 * the ADM parser definition yields a flat tree -- so its hint rendered but a
 * click had nothing to search and silently did nothing. This provider gets its
 * counts from the language server's `textDocument/codeLens` (one request for
 * the whole file) and a click opens the usage list of
 * [org.adm.intellij.search.ADMUsageSearch].
 */
class ADMUsagesCodeVisionProvider : DaemonBoundCodeVisionProvider {

	override val id: String = "ADM.usages"
	override val name: String = "ADM usages"
	override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top
	override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()

	override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
		if (file.fileType != ADMFileType.INSTANCE) return emptyList()
		val project = file.project
		val virtualFile = file.virtualFile ?: return emptyList()
		val uri = ADMReferences.lspUri(project, virtualFile)
		val document = editor.document

		val lenses = ADMCodeLenses.forFile(project, uri)
		if (lenses == null) {
			// No answer yet: have the daemon try again once the server is up.
			ADMServerRefresh.retryLater(project)
			return emptyList()
		}
		if (lenses.isEmpty()) return emptyList()

		val out = ArrayList<Pair<TextRange, CodeVisionEntry>>(lenses.size)
		for (lens in lenses) {
			// The test lens is ADMTestsCodeVisionProvider's.
			if (lens.command == ADMCodeLenses.GOTO_TEST) continue
			if (lens.position.line >= document.lineCount) continue
			val offset = document.getLineStartOffset(lens.position.line) + lens.position.character
			if (offset > document.textLength) continue
			val entry = ClickableTextCodeVisionEntry(
				lens.title,
				id,
				{ event, clickedEditor ->
					val clickedProject = clickedEditor.project
					if (clickedProject != null) {
						ADMUsageSearch.show(
							clickedProject,
							clickedEditor,
							virtualFile,
							offset,
							event?.let { RelativePoint(it) },
						)
					}
				},
			)
			out.add(TextRange(offset, offset) to entry)
		}
		return out
	}
}
