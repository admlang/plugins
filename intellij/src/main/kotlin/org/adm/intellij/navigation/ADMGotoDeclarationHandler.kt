package org.adm.intellij.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.adm.intellij.lang.ADMFileType
import org.adm.intellij.lsp.ADMReferences
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.net.URI
import java.nio.file.Paths

/**
 * Go to Declaration (Ctrl+B, Ctrl+click, F3) for ADM files, answered by our
 * own language server.
 *
 * The platform's LSP client provides this already, but every
 * `gotoDeclarationHandler` runs in registration order and the action stops at
 * the first non-null answer. lsp4ij registers one with `order="first"` for
 * every language; for a file it has no server for it answers with an empty
 * array, so the action reported "Cannot find declaration to go to" and the
 * platform handler behind it never ran. This handler is registered ahead of it
 * and asks `textDocument/definition` directly.
 */
class ADMGotoDeclarationHandler : GotoDeclarationHandler {

	override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
		val project = editor.project ?: return null
		val file = editor.virtualFile ?: sourceElement?.containingFile?.virtualFile ?: return null
		if (file.fileType != ADMFileType.INSTANCE) return null
		val server = ADMReferences.serverFor(project) ?: return null

		val params = DefinitionParams(
			TextDocumentIdentifier(ADMReferences.lspUri(project, file)),
			ADMReferences.positionOf(editor.document, offset),
		)
		val locations: List<Location> = try {
			val either = server.sendRequestSync(REQUEST_TIMEOUT_MS) { ls -> ls.textDocumentService.definition(params) }
			when {
				either == null -> emptyList()
				either.isLeft -> either.left.orEmpty().filterNotNull()
				else -> either.right.orEmpty().filterNotNull().map { Location(it.targetUri, it.targetSelectionRange ?: it.targetRange) }
			}
		} catch (t: Throwable) {
			log.warn("textDocument/definition failed for ${file.path}", t)
			return null
		}
		if (locations.isEmpty()) return null

		val psiManager = PsiManager.getInstance(project)
		val documents = PsiDocumentManager.getInstance(project)
		val targets = locations.mapNotNull { location ->
			val uri = location.uri ?: return@mapNotNull null
			val target = VirtualFileManager.getInstance().findFileByNioPath(Paths.get(URI(uri))) ?: return@mapNotNull null
			val psiFile = psiManager.findFile(target) ?: return@mapNotNull null
			val document = documents.getDocument(psiFile) ?: return@mapNotNull psiFile
			val start = location.range?.start ?: return@mapNotNull psiFile
			if (start.line >= document.lineCount) return@mapNotNull psiFile
			val targetOffset = (document.getLineStartOffset(start.line) + start.character).coerceIn(0, document.textLength)
			psiFile.findElementAt(targetOffset) ?: psiFile
		}
		return targets.toTypedArray().takeIf { it.isNotEmpty() }
	}

	companion object {
		private val log = Logger.getInstance(ADMGotoDeclarationHandler::class.java)

		/** Named on purpose; see ADMReferences.REQUEST_TIMEOUT_MS. */
		private const val REQUEST_TIMEOUT_MS = 10_000
	}
}
