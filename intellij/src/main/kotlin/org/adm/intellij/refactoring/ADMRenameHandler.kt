package org.adm.intellij.refactoring

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameHandler
import org.adm.intellij.lang.ADMFileType
import org.adm.intellij.lang.ADMIdentifiers
import org.adm.intellij.lsp.ADMReferences
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit

/**
 * Rename refactoring for ADM, served by `textDocument/rename`.
 *
 * The platform's rename refactoring needs a named PSI element to anchor on,
 * and the ADM parser definition yields a flat tree -- so Shift+F6 reported
 * "cannot perform refactoring" no matter what the caret was on. Resolution
 * lives in the compiler behind the LSP, which validates the target and hands
 * back the workspace-wide edit; this handler only asks for the new name and
 * applies what the server returns.
 */
class ADMRenameHandler : RenameHandler {
	private val log = Logger.getInstance(ADMRenameHandler::class.java)

	override fun isAvailableOnDataContext(context: DataContext): Boolean {
		val file = context.getData(CommonDataKeys.VIRTUAL_FILE) ?: return false
		val editor = context.getData(CommonDataKeys.EDITOR) ?: return false
		if (file.fileType != ADMFileType.INSTANCE) return false
		return ADMIdentifiers.at(editor.document, editor.caretModel.offset) != null
	}

	override fun invoke(project: Project, editor: Editor?, file: PsiFile?, context: DataContext?) {
		if (editor == null) return
		val virtualFile = file?.virtualFile ?: return
		val document = editor.document
		val current = ADMIdentifiers.at(document, editor.caretModel.offset) ?: return
		val site = ADMReferences.siteAtCaret(project, editor, virtualFile)

		val newName = Messages.showInputDialog(
			project,
			"Rename '$current' to:",
			"Rename ADM Symbol",
			null,
			current,
			null,
		)?.trim()
		if (newName.isNullOrEmpty() || newName == current) return

		ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Renaming '$current'", true) {
			override fun run(indicator: ProgressIndicator) {
				indicator.isIndeterminate = true
				rename(project, site, current, newName)
			}
		})
	}

	override fun invoke(project: Project, elements: Array<out PsiElement>, context: DataContext?) {
		// Editor-less invocations (project view) have no caret to resolve; the
		// editor overload is the entry point.
	}

	private fun rename(project: Project, site: ADMReferences.Site, current: String, newName: String) {
		val server = ADMReferences.serverFor(project) ?: run {
			notify(project, "Rename failed: the ADM language server is not running", NotificationType.ERROR)
			return
		}

		val params = RenameParams(TextDocumentIdentifier(site.uri), site.position, newName)
		val edit = try {
			// Explicit timeout: see ADMReferences.REQUEST_TIMEOUT_MS on why the
			// defaulted parameter must not be omitted.
			server.sendRequestSync(10_000) { ls -> ls.textDocumentService.rename(params) }
		} catch (t: Throwable) {
			log.warn("textDocument/rename failed for ${site.uri}", t)
			notify(project, "Rename failed: ${t.message ?: t.javaClass.simpleName}", NotificationType.ERROR)
			return
		}
		val changes = edit?.changes
		if (changes.isNullOrEmpty()) {
			notify(project, "Nothing to rename at the caret", NotificationType.WARNING)
			return
		}

		ApplicationManager.getApplication().invokeLater {
			var files = 0
			var edits = 0
			WriteCommandAction.runWriteCommandAction(project, "Rename '$current' to '$newName'", null, {
				for ((uri, textEdits) in changes) {
					val applied = applyEdits(uri, textEdits)
					if (applied > 0) {
						files++
						edits += applied
					}
				}
			})
			notify(project, "Renamed '$current' to '$newName': $edits usage(s) in $files file(s)", NotificationType.INFORMATION)
		}
	}

	/** Applies [textEdits] to the document behind [uri], last edit first so earlier offsets stay valid. */
	private fun applyEdits(uri: String, textEdits: List<TextEdit>): Int {
		val file = VirtualFileManager.getInstance().findFileByUrl(uri) ?: return 0
		val document = FileDocumentManager.getInstance().getDocument(file) ?: return 0
		val ordered = textEdits.sortedWith(
			compareByDescending<TextEdit> { it.range.start.line }.thenByDescending { it.range.start.character }
		)
		var applied = 0
		for (edit in ordered) {
			val start = offsetOf(document, edit.range.start.line, edit.range.start.character) ?: continue
			val end = offsetOf(document, edit.range.end.line, edit.range.end.character) ?: continue
			if (end < start) continue
			document.replaceString(start, end, edit.newText)
			applied++
		}
		return applied
	}

	private fun offsetOf(document: Document, line: Int, character: Int): Int? {
		if (line >= document.lineCount) return null
		val offset = document.getLineStartOffset(line) + character
		return offset.takeIf { it <= document.textLength }
	}

	private fun notify(project: Project, message: String, type: NotificationType) {
		ApplicationManager.getApplication().invokeLater {
			NotificationGroupManager.getInstance()
				.getNotificationGroup("ADM")
				.createNotification(message, type)
				.notify(project)
		}
	}
}
