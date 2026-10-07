package org.adm.intellij.codevision

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import org.adm.intellij.lang.ADMFileType
import org.adm.intellij.lsp.ADMCodeLenses
import org.adm.intellij.lsp.ADMReferences
import org.adm.intellij.lsp.ADMServerRefresh

/**
 * The "tested in N tests" hint beside a function's usage count.
 *
 * The language server ranks the `@test`s that call the function, the most
 * direct first: the sibling `_test.adm`, a test named after the function, a
 * suite named after its type and a call under `assert` all count above an
 * incidental call in an unrelated suite. One test: a click jumps to it.
 * Several: a click offers them in that order, so a widely used helper still
 * shows its own tests at the top.
 */
class ADMTestsCodeVisionProvider : DaemonBoundCodeVisionProvider {

	override val id: String = "ADM.tests"
	override val name: String = "ADM tests"
	override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top
	override val relativeOrderings: List<CodeVisionRelativeOrdering> =
		listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingAfter("ADM.usages"))

	override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
		if (file.fileType != ADMFileType.INSTANCE) return emptyList()
		val project = file.project
		val virtualFile = file.virtualFile ?: return emptyList()
		val uri = ADMReferences.lspUri(project, virtualFile)
		val document = editor.document

		val lenses = ADMCodeLenses.forFile(project, uri)
		if (lenses == null) {
			ADMServerRefresh.retryLater(project)
			return emptyList()
		}

		val out = ArrayList<Pair<TextRange, CodeVisionEntry>>()
		for (lens in lenses) {
			if (lens.command != ADMCodeLenses.GOTO_TEST) continue
			if (lens.position.line >= document.lineCount) continue
			val offset = document.getLineStartOffset(lens.position.line) + lens.position.character
			if (offset > document.textLength) continue
			val tests = lens.tests
			val entry = ClickableTextCodeVisionEntry(
				lens.title,
				id,
				{ event, clickedEditor ->
					val clickedProject = clickedEditor.project
					if (clickedProject != null && tests.isNotEmpty()) {
						if (tests.size == 1 || event == null) {
							open(clickedProject, tests[0])
						} else {
							JBPopupFactory.getInstance()
								.createPopupChooserBuilder(tests)
								.setTitle("Tests")
								.setRenderer(SimpleListCellRenderer.create("") { it.label })
								.setItemChosenCallback { open(clickedProject, it) }
								.createPopup()
								.show(RelativePoint(event))
						}
					}
				},
			)
			out.add(TextRange(offset, offset) to entry)
		}
		return out
	}

	private fun open(project: Project, test: ADMCodeLenses.TestRef) {
		val file = VirtualFileManager.getInstance().findFileByUrl(test.uri) ?: return
		OpenFileDescriptor(project, file, test.line, test.column).navigate(true)
	}
}
