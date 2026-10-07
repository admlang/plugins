package org.adm.intellij.lsp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.lsp.api.LspServerManager
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Asks the server where a symbol is declared, and whether the caret is already
 * sitting on that declaration.
 */
object ADMDeclarationProbe {
	private val log = Logger.getInstance(ADMDeclarationProbe::class.java)

	data class Target(val file: VirtualFile?, val uri: String, val line: Int, val column: Int)

	/**
	 * The declaration the symbol at [site] points to, or null. Runs the request
	 * synchronously, so call it off the EDT -- and note [site] carries plain
	 * values precisely so no Editor is touched here.
	 */
	fun definitionAt(project: Project, site: ADMReferences.Site): Target? {
		val server = LspServerManager.getInstance(project)
			.getServersForProvider(ADMLspServerSupportProvider::class.java)
			.firstOrNull() ?: return null

		val params = DefinitionParams().apply {
			textDocument = TextDocumentIdentifier(site.uri)
			position = site.position
		}

		val result = try {
			// Explicit timeout: see ADMReferences.REQUEST_TIMEOUT_MS on why the
			// defaulted parameter must not be omitted.
			server.sendRequestSync(10_000) { ls -> ls.textDocumentService.definition(params) }
		} catch (t: Throwable) {
			log.warn("textDocument/definition failed", t)
			return null
		} ?: return null

		// The response is either Location[] or LocationLink[].
		if (result.isLeft) {
			val location = result.left?.firstOrNull() ?: return null
			val start = location.range?.start ?: return null
			return target(location.uri, start)
		}
		if (result.isRight) {
			val link = result.right?.firstOrNull() ?: return null
			val start = link.targetSelectionRange?.start ?: link.targetRange?.start ?: return null
			return target(link.targetUri, start)
		}
		return null
	}

	/**
	 * Whether [target] is the declaration at [site] itself. Compares line rather
	 * than exact column, so a caret placed mid-identifier still counts.
	 */
	fun isSelf(target: Target, site: ADMReferences.Site): Boolean =
		target.uri.trimEnd('/') == site.uri.trimEnd('/') && target.line == site.position.line

	private fun target(uri: String?, position: Position): Target? {
		if (uri == null) return null
		return Target(
			file = VirtualFileManager.getInstance().findFileByUrl(uri),
			uri = uri,
			line = position.line,
			column = position.character,
		)
	}

}
