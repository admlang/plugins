package org.adm.intellij.documentation

import com.intellij.codeInsight.documentation.render.DocRenderManager
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.adm.intellij.lang.ADMFileType
import org.adm.intellij.settings.ADMSettingsState

/**
 * Opens ADM editors with doc comments rendered when the ADM setting asks for
 * it, independent of the IDE-wide "Render documentation comments" option.
 * The platform's per-editor and per-comment toggles keep working on top.
 */
class ADMDocRenderDefault : EditorFactoryListener {
	override fun editorCreated(event: EditorFactoryEvent) {
		if (!ADMSettingsState.getInstance().renderDocComments) return
		val editor = event.editor
		val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
		if (file.fileType != ADMFileType.INSTANCE) return
		DocRenderManager.setDocRenderingEnabled(editor, true)
	}
}
