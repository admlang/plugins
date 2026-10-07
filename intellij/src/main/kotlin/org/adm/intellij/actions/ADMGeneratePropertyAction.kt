package org.adm.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.adm.intellij.lang.ADMFileType

/**
 * Generate › Getter / Setter / Getter and Setter on a field line inside a
 * type: rewrites `name T` (or `name T = init`) as the property block the
 * spec describes, storage in `value` plus the chosen accessors:
 *
 *     balance float {
 *         value float
 *
 *         def get() float { return value }
 *         def set(val float) { value = val }
 *     }
 */
open class ADMGeneratePropertyAction(private val getter: Boolean, private val setter: Boolean) : DumbAwareAction(
	when {
		getter && setter -> "Getter and Setter"
		getter -> "Getter"
		else -> "Setter"
	},
	"Turn the field at the caret into a property with the chosen accessors",
	null,
) {
	/** A field line: modifiers, name, type, optional initializer. */
	private class Field(val indent: String, val modifiers: String, val name: String, val type: String, val init: String?)

	override fun getActionUpdateThread() = ActionUpdateThread.BGT

	override fun update(e: AnActionEvent) {
		val file = e.getData(CommonDataKeys.PSI_FILE)
		val editor = e.getData(CommonDataKeys.EDITOR)
		e.presentation.isEnabledAndVisible = file != null && editor != null && file.fileType == ADMFileType.INSTANCE &&
			ADMTypeAtCaret.at(file, editor, FIELD_HOLDERS) != null && fieldAt(editor) != null
	}

	override fun actionPerformed(e: AnActionEvent) {
		val project = e.project ?: return
		val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
		val editor = e.getData(CommonDataKeys.EDITOR) ?: return
		val field = fieldAt(editor) ?: return
		val document = editor.document
		val line = document.getLineNumber(editor.caretModel.offset)
		val start = document.getLineStartOffset(line)
		val end = document.getLineEndOffset(line)
		val inner = field.indent + "\t"
		val sb = StringBuilder()
		sb.append(field.indent).append(field.modifiers).append(field.name).append(' ').append(field.type).append(" {\n")
		sb.append(inner).append("value ").append(field.type)
		field.init?.let { sb.append(" = ").append(it) }
		sb.append("\n\n")
		if (getter) sb.append(inner).append("def get() ").append(field.type).append(" { return value }\n")
		if (setter) sb.append(inner).append("def set(val ").append(field.type).append(") { value = val }\n")
		sb.append(field.indent).append('}')
		WriteCommandAction.runWriteCommandAction(project, templatePresentation.text, null, {
			document.replaceString(start, end, sb.toString())
			PsiDocumentManager.getInstance(project).commitDocument(document)
			editor.caretModel.moveToOffset(start + sb.indexOf("def "))
			editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
		}, file)
	}

	/** The field the caret line declares, or null when the line is anything else. */
	private fun fieldAt(editor: Editor): Field? {
		val document = editor.document
		val line = document.getLineNumber(editor.caretModel.offset)
		val text = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
		val m = FIELD_LINE.matchEntire(text.substringBefore("//").trimEnd()) ?: return null
		val (indent, modifiers, name, type, init) = m.destructured
		if (name in KEYWORDS || type.contains('{') || type.contains('(')) return null
		return Field(indent, modifiers, name, type.trim(), init.trim().ifEmpty { null })
	}

	private companion object {
		val FIELD_HOLDERS = setOf("type", "struct", "service", "component")
		val FIELD_LINE = Regex("""^([ \t]*)((?:(?:internal|weak|atomic)\s+)*)([A-Za-z_]\w*)\s+([^=]+?)(?:\s*=\s*(.+))?$""")
		val KEYWORDS = setOf("def", "type", "struct", "enum", "union", "interface", "datatype", "service", "component", "view", "style", "use", "let", "const", "return", "if", "for", "while", "match", "check", "partial", "async", "meta")
	}
}

class ADMGenerateGetterAction : ADMGeneratePropertyAction(getter = true, setter = false)
class ADMGenerateSetterAction : ADMGeneratePropertyAction(getter = false, setter = true)
class ADMGenerateGetterSetterAction : ADMGeneratePropertyAction(getter = true, setter = true)
