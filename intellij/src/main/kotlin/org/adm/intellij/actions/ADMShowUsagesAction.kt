package org.adm.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import org.adm.intellij.lang.ADMFileType
import org.adm.intellij.search.ADMUsageSearch

/**
 * Show Usages for the ADM symbol at the caret.
 *
 * ADM has no PSI to speak of -- the parser definition yields a flat tree and
 * real resolution lives in the compiler behind the LSP -- so the platform's
 * PSI-driven Find Usages has nothing to search. This opens
 * [org.adm.intellij.search.ADMUsageSearch]'s list, which resolves through
 * `textDocument/references`.
 */
class ADMShowUsagesAction : AnAction(), DumbAware {

	override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

	override fun update(e: AnActionEvent) {
		val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
		val editor = e.getData(CommonDataKeys.EDITOR)
		e.presentation.isEnabledAndVisible =
			e.project != null && editor != null && file != null && file.fileType == ADMFileType.INSTANCE
	}

	override fun actionPerformed(e: AnActionEvent) {
		val project = e.project ?: return
		val editor = e.getData(CommonDataKeys.EDITOR) ?: return
		val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
		ADMUsageSearch.show(project, editor, file, editor.caretModel.offset)
	}
}
