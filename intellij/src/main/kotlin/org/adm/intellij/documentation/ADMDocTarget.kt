package org.adm.intellij.documentation

import com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter
import com.intellij.model.Pointer
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.pom.Navigatable
import org.adm.intellij.lsp.ADMReferences
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceSymbolParams

/**
 * A declaration as a documentation target: the owner of a rendered doc
 * comment, or what a See Also link resolved to. Navigates to the
 * declaration; its documentation is the language server's hover card there.
 */
class ADMDocTarget(val project: Project, val file: VirtualFile, val offset: Int, val name: String) : DocumentationTarget {
	override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

	override fun computePresentation(): TargetPresentation = TargetPresentation.builder(name).locationText(file.name).presentation()

	override val navigatable: Navigatable
		get() = OpenFileDescriptor(project, file, offset)

	override fun computeDocumentation(): DocumentationResult? {
		val markdown = hoverMarkdown() ?: return null
		return DocumentationResult.documentation(DocMarkdownToHtmlConverter.convert(project, markdown, null))
	}

	private fun hoverMarkdown(): String? {
		val server = ADMReferences.serverFor(project) ?: return null
		val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
		val params = HoverParams(TextDocumentIdentifier(ADMReferences.lspUri(project, file)), ADMReferences.positionOf(document, offset))
		val hover = try {
			server.sendRequestSync(10_000) { ls -> ls.textDocumentService.hover(params) }
		} catch (t: Throwable) {
			log.warn("textDocument/hover failed", t)
			return null
		} ?: return null
		val contents = hover.contents ?: return null
		return if (contents.isRight) contents.right?.value else contents.left?.firstOrNull()?.let { if (it.isLeft) it.left else it.right?.value }
	}

	companion object {
		private val log = Logger.getInstance(ADMDocTarget::class.java)

		/**
		 * The declaration a See Also target names: through workspace/symbol
		 * (module-level names; for `Type.member` and `name(...)` forms the
		 * type or the name), else a declaration of that name in [near], the
		 * file the comment lives in, which covers what the server does not
		 * list. Runs a request synchronously, so call it off the EDT.
		 */
		fun find(project: Project, reference: String, near: VirtualFile? = null): ADMDocTarget? =
			findThroughServer(project, reference) ?: near?.let { findInFile(project, reference, it) }

		/** A declaration of the name in [file]: `def name`, `type name`, `const name`, or `name =` inside a `const (` block. */
		private fun findInFile(project: Project, reference: String, file: VirtualFile): ADMDocTarget? {
			val name = reference.substringBefore('(').trim().substringAfterLast('.')
			if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' }) return null
			val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
			val text = document.charsSequence
			val declaring = Regex("""(?m)^[ \t]*(?:(?:def|type|struct|enum|union|interface|datatype|service|component|const|meta|module|check)\s+(?:\w+\s+)*)?(${Regex.escape(name)})\b(?=\s*(?:[(<{=,:]|\s|$))""")
			val hit = declaring.findAll(text).firstOrNull { m ->
				val line = text.subSequence(m.range.first, text.indexOf('\n', m.range.first).let { if (it < 0) text.length else it }).toString().trim()
				// A line that only reads or assigns the name is not a declaration.
				!line.startsWith("return") && !line.startsWith("let ") && (line.startsWith(name) && (line.substringAfter(name).trimStart().startsWith("=") || line.substringAfter(name).trimStart().startsWith(",")) || Regex("""^(def|type|struct|enum|union|interface|datatype|service|component|const|meta|module|check)\b""").containsMatchIn(line))
			} ?: return null
			return ADMDocTarget(project, file, hit.groups[1]!!.range.first, name)
		}

		private fun findThroughServer(project: Project, reference: String): ADMDocTarget? {
			val server = ADMReferences.serverFor(project) ?: return null
			val bare = reference.substringBefore('(').trim()
			val simple = bare.substringBefore('.')
			val container = bare.substringAfterLast('.', "").takeIf { '.' in bare }
			val wanted = if (container != null && bare.count { it == '.' } == 1) listOf(bare.substringAfterLast('.'), simple) else listOf(simple)
			val result = try {
				server.sendRequestSync(10_000) { ls -> ls.workspaceService.symbol(WorkspaceSymbolParams(wanted.first())) }
			} catch (t: Throwable) {
				log.warn("workspace/symbol failed", t)
				return null
			} ?: return null
			data class Hit(val name: String, val container: String?, val uri: String?, val line: Int, val character: Int)
			val hits: List<Hit> = when {
				result.isLeft -> result.left.orEmpty().map { Hit(it.name, it.containerName, it.location?.uri, it.location?.range?.start?.line ?: 0, it.location?.range?.start?.character ?: 0) }
				result.isRight -> result.right.orEmpty().mapNotNull { s ->
					val loc = s.location?.takeIf { it.isLeft }?.left ?: return@mapNotNull null
					Hit(s.name, s.containerName, loc.uri, loc.range?.start?.line ?: 0, loc.range?.start?.character ?: 0)
				}
				else -> emptyList()
			}
			val hit = wanted.firstNotNullOfOrNull { name ->
				hits.filter { it.name.equals(name, ignoreCase = true) }.let { exact ->
					exact.firstOrNull { h -> container != null && h.container?.endsWith(container) == true } ?: exact.firstOrNull()
				}
			} ?: return null
			val uri = hit.uri ?: return null
			val file = VirtualFileManager.getInstance().findFileByUrl(uri) ?: return null
			val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
			if (hit.line >= document.lineCount) return null
			return ADMDocTarget(project, file, document.getLineStartOffset(hit.line) + hit.character, hit.name)
		}
	}
}

/**
 * Resolves the `adm-see://<target>` links of a rendered doc comment's See
 * Also row: the editor goes to the declaration named, and the card the
 * platform opens for the click is that declaration's. A target nothing
 * resolves stays on the comment's own card with a notification — the link
 * is never handed to the desktop browser, which is what the platform does
 * with any link no handler claims.
 */
class ADMSeeAlsoLinkHandler : DocumentationLinkHandler {
	override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
		if (!url.startsWith(SCHEME)) return null
		val reference = url.removePrefix(SCHEME).trim()
		val owner = target as? ADMDocTarget
		val project = owner?.project ?: ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed } ?: return null
		val resolved = if (reference.isEmpty()) null else ADMDocTarget.find(project, reference, owner?.file)
		if (resolved == null) {
			log.info("See Also target $reference: no declaration found")
			ApplicationManager.getApplication().invokeLater({
				NotificationGroupManager.getInstance().getNotificationGroup("ADM").createNotification("See Also: no declaration named $reference was found", NotificationType.WARNING).notify(project)
			}, project.disposed)
			return LinkResolveResult.resolvedTarget(target)
		}
		log.info("See Also target $reference -> ${resolved.file.path}@${resolved.offset}")
		ApplicationManager.getApplication().invokeLater({ resolved.navigatable.navigate(true) }, project.disposed)
		return LinkResolveResult.resolvedTarget(resolved)
	}

	companion object {
		const val SCHEME = "adm-see://"
		private val log = Logger.getInstance(ADMSeeAlsoLinkHandler::class.java)
	}
}
