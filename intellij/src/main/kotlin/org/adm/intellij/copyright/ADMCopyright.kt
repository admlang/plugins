package org.adm.intellij.copyright

import com.intellij.copyright.CopyrightManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.maddyhome.idea.copyright.CopyrightProfile
import com.maddyhome.idea.copyright.options.LanguageOptions
import com.maddyhome.idea.copyright.pattern.EntityUtil
import com.maddyhome.idea.copyright.pattern.VelocityHelper
import com.maddyhome.idea.copyright.psi.UpdateCopyright
import com.maddyhome.idea.copyright.psi.UpdateCopyrightsProvider
import com.maddyhome.idea.copyright.psi.UpdatePsiFileCopyright
import org.adm.intellij.lang.ADMFileType

/**
 * Generate › Copyright: the project's copyright notice (Settings › Editor ›
 * Copyright) as `//` lines at the top of the file, followed by a blank line
 * so it never runs into a doc comment below it. Does nothing when the file
 * already starts with that notice.
 */
class ADMInsertCopyrightAction : DumbAwareAction("Copyright", "Insert the project's copyright notice at the top of the file", null) {
	override fun getActionUpdateThread() = ActionUpdateThread.BGT

	override fun update(e: AnActionEvent) {
		val file = e.getData(CommonDataKeys.PSI_FILE)
		e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null && file?.fileType == ADMFileType.INSTANCE
	}

	override fun actionPerformed(e: AnActionEvent) {
		val project = e.project ?: return
		val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
		val editor = e.getData(CommonDataKeys.EDITOR) ?: return
		val profile = CopyrightManager.getInstance(project).getCopyrightOptions(file)
		if (profile == null) {
			NotificationGroupManager.getInstance().getNotificationGroup("ADM")
				.createNotification("No copyright profile applies to this file; define one under Settings › Editor › Copyright", NotificationType.WARNING)
				.notify(project)
			return
		}
		val notice = ADMCopyrightNotice.render(project, file, profile)
		if (notice.isEmpty()) return
		val document = editor.document
		val text = document.charsSequence
		if (text.startsWith(notice)) {
			editor.caretModel.moveToOffset(0)
			return
		}
		WriteCommandAction.runWriteCommandAction(project, "Insert Copyright", null, {
			val gap = if (text.startsWith("\n")) "\n" else "\n\n"
			document.insertString(0, notice + gap)
		}, file)
	}
}

object ADMCopyrightNotice {
	/** The profile's notice with its variables filled in, as `//` lines without a trailing newline. */
	fun render(project: Project, file: PsiFile, profile: CopyrightProfile): String {
		val module: Module? = ModuleUtilCore.findModuleForPsiElement(file)
		val raw = VelocityHelper.evaluate(file, project, module, EntityUtil.decode(profile.notice))
		return raw.trimEnd().lineSequence().joinToString("\n") { line -> if (line.isBlank()) "//" else "// $line" }
	}
}

/**
 * Makes Code › Update Copyright work on ADM files: the notice lives in the
 * `//` comments before the first declaration, with a blank line after it.
 */
class ADMUpdateCopyrightsProvider : UpdateCopyrightsProvider() {
	override fun createInstance(project: Project, module: Module?, file: VirtualFile, base: FileType, options: CopyrightProfile): UpdateCopyright =
		object : UpdatePsiFileCopyright(project, module, file, options) {
			override fun scanFile() {
				val first = getFile().firstChild ?: return
				var last = first
				var e: com.intellij.psi.PsiElement? = first
				while (e != null && (e is PsiComment || e is PsiWhiteSpace)) {
					last = e
					e = getNextSibling(e)
				}
				checkComments(first, last, true)
			}
		}

	override fun getDefaultOptions(): LanguageOptions = createDefaultOptions(false).apply {
		isBlock = false
		isPrefixLines = true
		isAddBlankAfter = true
		isSeparateBefore = false
		isSeparateAfter = false
	}
}
