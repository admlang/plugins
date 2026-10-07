package org.adm.intellij.search

import com.intellij.find.actions.ShowUsagesAction
import com.intellij.ide.DataManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.awt.RelativePoint
import org.adm.intellij.lang.ADMIdentifiers
import org.adm.intellij.lsp.ADMReferences

/**
 * Opens the platform's Show Usages popup -- the same table, filter toolbar and
 * Find-tool-window handoff other languages get -- for the ADM symbol at an
 * offset. Call on the EDT.
 */
object ADMUsageSearch {

	fun show(project: Project, editor: Editor, file: VirtualFile, offset: Int, at: RelativePoint? = null) {
		val name = ADMIdentifiers.at(editor.document, offset) ?: return
		val position = ADMReferences.positionOf(editor.document, offset)
		val target = ADMSearchTarget(name, ADMReferences.lspUri(project, file), position.line, position.character)
		val point = at ?: JBPopupFactory.getInstance().guessBestPopupLocation(editor)
		val context = DataManager.getInstance().getDataContext(editor.contentComponent)
		ShowUsagesAction.showUsages(project, context, point, target)
	}
}
