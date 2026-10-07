package org.adm.intellij.lsp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Reference lookup driven straight off the ADM language server.
 *
 * `lspFindReferencesSupport` alone did not give ADM a working usage list, so
 * rather than depend on how the platform wires LSP find-usages, this asks the
 * server for `textDocument/references` itself. The server has answered that
 * request correctly all along; only the editor side was missing.
 */
object ADMReferences {
	private val log = Logger.getInstance(ADMReferences::class.java)

	data class Ref(
		val file: VirtualFile?,
		val path: String,
		val line: Int,
		val column: Int,
		val endLine: Int = line,
		val endColumn: Int = column,
		/** "read", "write", "readwrite", or null when the server did not say. */
		val access: String? = null,
	)

	/**
	 * Outcome of a lookup. An empty list had three very different causes that
	 * all rendered as the same blank popup -- no server, a failed request, or a
	 * symbol genuinely without usages -- so the reason travels with the result
	 * and is shown to the user.
	 */
	sealed interface Result {
		data class Found(val refs: List<Ref>) : Result
		object Empty : Result
		object NoServer : Result
		data class Failed(val reason: String) : Result
	}

	/**
	 * A resolved query site: everything the LSP request needs, with no Editor
	 * left in it.
	 *
	 * Editor models -- caret, document -- may only be touched on the EDT or
	 * under a read action, so they are read once while building this and never
	 * again. Passing the Editor into the background task instead threw
	 * "Access to Editor models is allowed either from EDT, or under read action".
	 */
	data class Site(val uri: String, val position: Position)

	/**
	 * Timeout for a synchronous LSP call, passed explicitly on purpose.
	 *
	 * `sendRequestSync(timeoutMs: Int = ..., sender)` has a defaulted first
	 * parameter, so omitting it makes Kotlin emit a call to the synthetic
	 * `sendRequestSync$default` bridge -- which is absent from some IDE builds
	 * and fails at runtime with NoSuchMethodError. Naming every argument keeps
	 * the call binary-compatible.
	 */
	private const val REQUEST_TIMEOUT_MS = 10_000

	/** Builds a [Site] from the caret. Call on the EDT. */
	fun siteAtCaret(project: Project, editor: Editor, file: VirtualFile): Site =
		Site(lspUri(project, file), positionOf(editor.document, editor.caretModel.offset))

	/** Builds a [Site] from an explicit offset. Call on the EDT. */
	fun siteAt(project: Project, editor: Editor, file: VirtualFile, offset: Int): Site =
		Site(lspUri(project, file), positionOf(editor.document, offset))

	/**
	 * References to the symbol at [site], declaration included. Runs the LSP
	 * request synchronously, so call it off the EDT.
	 */
	fun at(project: Project, site: Site): Result {
		val server = serverFor(project) ?: return Result.NoServer

		val params = ReferenceParams().apply {
			textDocument = TextDocumentIdentifier(site.uri)
			position = site.position
			// The declaration is not a usage: the popup lists it separately
			// nowhere, and the code vision lens counts exclude it too.
			context = ReferenceContext(false)
		}

		// adm/references carries the access kind the read/write usage filters
		// need; an older server without it answers with an error, and the
		// standard request covers the rest.
		val refs: List<Pair<org.eclipse.lsp4j.Location, String?>> = try {
			val extended = server.sendRequestSync(REQUEST_TIMEOUT_MS) { ls ->
				(ls as ADMLanguageServer).admReferences(params)
			}
			extended?.mapNotNull { r ->
				val uri = r.uri ?: return@mapNotNull null
				val range = r.range ?: return@mapNotNull null
				org.eclipse.lsp4j.Location(uri, range) to r.access
			} ?: emptyList()
		} catch (t: Throwable) {
			log.info("adm/references unavailable (${t.message}); falling back to textDocument/references")
			val locations = try {
				server.sendRequestSync(REQUEST_TIMEOUT_MS) { ls -> ls.textDocumentService.references(params) }
			} catch (t2: Throwable) {
				log.warn("textDocument/references failed for ${site.uri}", t2)
				return Result.Failed(t2.message ?: t2.javaClass.simpleName)
			}
			if (locations == null) {
				log.warn("textDocument/references returned no result for ${site.uri} at ${site.position.line}:${site.position.character}")
				return Result.Failed("server returned no result for ${shortUri(site.uri)}")
			}
			locations.filterNotNull().map { it to null }
		}
		log.info("references: ${refs.size} location(s) for ${site.uri} at ${site.position.line}:${site.position.character}")

		val out = ArrayList<Ref>(refs.size)
		for ((location, access) in refs) {
			val uri = location.uri ?: continue
			val start = location.range?.start ?: continue
			val end = location.range?.end ?: start
			out.add(
				Ref(
					file = VirtualFileManager.getInstance().findFileByUrl(uri),
					path = pathOf(uri),
					line = start.line,
					column = start.character,
					endLine = end.line,
					endColumn = end.character,
					access = access,
				)
			)
		}
		return if (out.isEmpty()) Result.Empty else Result.Found(out)
	}

	private fun shortUri(uri: String): String = uri.substringAfterLast('/')

	fun serverFor(project: Project): LspServer? {
		val servers = LspServerManager.getInstance(project)
			.getServersForProvider(ADMLspServerSupportProvider::class.java)
		if (servers.isEmpty()) {
			log.warn("no ADM language server is running for this project")
			return null
		}
		return servers.firstOrNull()
	}

	/**
	 * LSP positions count UTF-16 code units within a line, which is what a Java
	 * `CharSequence` offset already measures -- so the column is the plain
	 * distance from the line start.
	 */
	fun positionOf(document: Document, offset: Int): Position {
		val safe = offset.coerceIn(0, document.textLength)
		val line = document.getLineNumber(safe)
		return Position(line, safe - document.getLineStartOffset(line))
	}

	/**
	 * The document URI to send to the server.
	 *
	 * Taken from the server descriptor, which is the same conversion the LSP
	 * client uses for `didOpen`. Hand-rolling it risked a different spelling of
	 * the same path -- and a URI the server has never seen resolves to no
	 * document, which comes back as zero references and looks exactly like a
	 * symbol with no usages.
	 */
	fun lspUri(project: Project, file: VirtualFile): String {
		val fromDescriptor = serverFor(project)?.descriptor?.let { descriptor ->
			runCatching { descriptor.getFileUri(file) }.getOrNull()
		}
		if (!fromDescriptor.isNullOrBlank()) return fromDescriptor

		val url = file.url
		return if (url.startsWith("file://") && !url.startsWith("file:///")) {
			"file:///" + url.removePrefix("file://").trimStart('/')
		} else {
			url
		}
	}

	private fun pathOf(uri: String): String =
		uri.removePrefix("file://").let { if (it.startsWith("/")) it else "/$it" }
}
