package org.adm.intellij.ide

import com.intellij.codeInsight.documentation.DocumentationActionProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.PsiDocCommentBase
import org.adm.intellij.lang.ADMFileType
import org.adm.intellij.lsp.ADMDeclarationProbe
import org.adm.intellij.lsp.ADMReferences

/**
 * "Show in ADM Documentation": opens the tool window's Documentation tab on
 * the declaration of the symbol at the caret, resolved through the language
 * server. In the editor's context menu, and in the corner menu of the hover
 * card through [ADMDocumentationActionProvider]. The bound form carries the
 * editor the card belongs to; the registered form reads it from the event.
 */
class ADMShowDocumentationAction(private val boundEditor: Editor? = null, private val boundFile: VirtualFile? = null) :
	DumbAwareAction("Show in ADM Documentation", "Open the ADM tool window's Documentation tab on the symbol at the caret", AllIcons.Toolwindows.Documentation) {

	override fun getActionUpdateThread() = ActionUpdateThread.BGT

	override fun update(e: AnActionEvent) {
		val file = boundFile ?: e.getData(CommonDataKeys.VIRTUAL_FILE)
		e.presentation.isEnabledAndVisible = (boundEditor ?: e.getData(CommonDataKeys.EDITOR)) != null && file?.fileType == ADMFileType.INSTANCE
	}

	override fun actionPerformed(e: AnActionEvent) {
		val project = e.project ?: return
		val editor = boundEditor ?: e.getData(CommonDataKeys.EDITOR) ?: return
		val file = boundFile ?: e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
		show(project, editor, file)
	}

	companion object {
		/** Resolves the caret's symbol to its declaration off the EDT, then reveals it; the caret itself when nothing resolves. */
		fun show(project: Project, editor: Editor, file: VirtualFile) {
			val site = ADMReferences.siteAtCaret(project, editor, file)
			val caretLine = editor.caretModel.logicalPosition.line
			ApplicationManager.getApplication().executeOnPooledThread {
				val target = ADMDeclarationProbe.definitionAt(project, site)
				val path = target?.file?.path ?: file.path
				val line = (target?.takeIf { it.file != null }?.line ?: caretLine) + 1
				ApplicationManager.getApplication().invokeLater({ ADMDocumentationPanel.reveal(project, path, line) }, project.disposed)
			}
		}
	}
}

/**
 * Handles the `adm-doc://<module>/<name>` link the language server puts in
 * the header row of every hover card (the icon at the right edge): opens the
 * ADM tool window's Documentation tab on that symbol and leaves the card
 * where it is.
 *
 * The platform asks every registered handler before it treats a link as
 * external, so this runs first; a `null` here would send the `adm-doc:`
 * URL to the OS browser instead, which is why the card's own target is
 * returned as the resolution.
 */
class ADMDocumentationLinkHandler : com.intellij.platform.backend.documentation.DocumentationLinkHandler {
	override fun resolveLink(target: com.intellij.platform.backend.documentation.DocumentationTarget, url: String): com.intellij.platform.backend.documentation.LinkResolveResult? {
		if (url.startsWith(SOURCE_SCHEME)) return openSource(target, url.removePrefix(SOURCE_SCHEME))
		if (!url.startsWith(SCHEME)) {
			log.debug("documentation link left to the platform: $url")
			return null
		}
		val rest = url.removePrefix(SCHEME)
		val module = rest.substringBefore('/')
		val name = rest.substringAfter('/', "")
		if (module.isEmpty() || name.isEmpty()) {
			log.warn("adm-doc link without module or name: $url")
			return null
		}
		// The target knows no project; the frame the card was opened from is
		// the best guess, then any open project.
		val project = openProject()
		if (project == null) {
			log.warn("adm-doc link $url: no open project")
			return null
		}
		log.info("adm-doc link $url -> ${project.name}")
		ApplicationManager.getApplication().invokeLater({ ADMDocumentationPanel.revealSymbol(project, module, name) }, project.disposed)
		return com.intellij.platform.backend.documentation.LinkResolveResult.resolvedTarget(target)
	}

	/** `adm-src://file:///path#L12`: opens the file at that line in the editor. */
	private fun openSource(target: com.intellij.platform.backend.documentation.DocumentationTarget, rest: String): com.intellij.platform.backend.documentation.LinkResolveResult? {
		val uri = rest.substringBefore("#L")
		val line = rest.substringAfter("#L", "").toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0
		val file = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(uri)
		val project = openProject()
		if (file == null || project == null) {
			log.warn("adm-src link $rest: file or project not found")
			return null
		}
		log.info("adm-src link $rest -> ${file.path}:${line + 1}")
		ApplicationManager.getApplication().invokeLater({ com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, line, 0).navigate(true) }, project.disposed)
		return com.intellij.platform.backend.documentation.LinkResolveResult.resolvedTarget(target)
	}

	/** The project the card was opened from: the focused frame's, else any open one. */
	private fun openProject(): Project? {
		val projects = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
		return IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project?.takeIf { !it.isDisposed && it in projects } ?: projects.firstOrNull()
	}

	private companion object {
		const val SCHEME = "adm-doc://"
		const val SOURCE_SCHEME = "adm-src://"
		val log = Logger.getInstance(ADMDocumentationLinkHandler::class.java)
	}
}

/** Adds the action to the documentation card's corner menu for ADM files. */
class ADMDocumentationActionProvider : DocumentationActionProvider {
	override fun additionalActions(editor: Editor, docComment: PsiDocCommentBase?, docText: String?): List<AnAction> {
		val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return emptyList()
		if (file.fileType != ADMFileType.INSTANCE) return emptyList()
		return listOf(ADMShowDocumentationAction(editor, file))
	}
}
