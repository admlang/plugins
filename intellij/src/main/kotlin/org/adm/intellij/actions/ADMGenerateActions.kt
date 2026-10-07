package org.adm.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.adm.intellij.lang.ADMFileType
import org.adm.intellij.structure.ADMDeclarations

/**
 * The type declaration the caret is in, as the declaration scanner sees it,
 * with the editing the Generate actions need: what the body already holds
 * and where a new member goes.
 */
class ADMTypeAtCaret(val node: ADMDeclarations.Node, private val document: Document) {
	/** The keyword the declaration used: `type`, `struct`, `interface`, … */
	val keyword: String get() = node.detail

	/** Names of the `def`s declared directly in the body. */
	val methodNames: Set<String> get() = node.children.filter { it.kind == ADMDeclarations.Kind.Function }.map { it.name }.toSet()

	/** The text between the braces. */
	val bodyText: String
		get() {
			val open = openBrace ?: return ""
			val close = closeBrace ?: return ""
			return if (close > open) document.getText(com.intellij.openapi.util.TextRange(open + 1, close)) else ""
		}

	/** First identifier of each body line: fields, properties, methods. */
	val memberNames: Set<String>
		get() = bodyText.lineSequence()
			.map { it.trim().removePrefix("def ").removePrefix("internal ").removePrefix("weak ").removePrefix("atomic ").trim() }
			.filter { it.isNotEmpty() && it[0].isLetter() }
			.map { it.takeWhile { c -> c.isLetterOrDigit() || c == '_' } }
			.toSet()

	private val openBrace: Int?
		get() {
			val text = document.charsSequence
			var i = node.offset
			val end = minOf(node.endOffset, text.length)
			while (i < end) {
				if (text[i] == '{') return i
				i++
			}
			return null
		}

	private val closeBrace: Int?
		get() {
			val text = document.charsSequence
			var i = minOf(node.endOffset, text.length) - 1
			while (i >= node.offset) {
				if (text[i] == '}') return i
				i--
			}
			return null
		}

	/** The indentation of the declaration line, so members sit one level in. */
	private val indent: String
		get() {
			val line = document.getLineNumber(node.offset)
			val start = document.getLineStartOffset(line)
			return document.getText(com.intellij.openapi.util.TextRange(start, node.offset)).takeWhile { it == ' ' || it == '\t' }
		}

	/**
	 * Appends [members] (each already written for one indentation level, lines
	 * separated by `\n`) at the end of the body, one blank line apart from
	 * what precedes them. Returns the offset of the first inserted line.
	 */
	fun append(members: List<String>): Int? {
		val close = closeBrace ?: return null
		val outer = indent
		val inner = "$outer\t"
		val before = document.getText(com.intellij.openapi.util.TextRange(openBrace?.plus(1) ?: close, close))
		val empty = before.isBlank()
		val sb = StringBuilder()
		if (!empty) sb.append('\n')
		for ((i, m) in members.withIndex()) {
			if (i > 0) sb.append('\n')
			for (line in m.split('\n')) {
				sb.append(if (line.isEmpty()) "" else inner + line).append('\n')
			}
		}
		// The closing brace keeps the declaration's own indentation.
		val lineStart = document.getLineStartOffset(document.getLineNumber(close))
		val braceIndent = document.getText(com.intellij.openapi.util.TextRange(lineStart, close))
		val at: Int
		if (braceIndent.isBlank() && braceIndent.isNotEmpty()) {
			at = lineStart
		} else if (braceIndent.isEmpty()) {
			at = close
		} else {
			// `{ ... }` on one line: break it.
			sb.insert(0, '\n')
			sb.append(outer)
			at = close
		}
		document.insertString(at, sb.toString())
		return at + (if (empty) 0 else 1)
	}

	companion object {
		private val TYPE_KINDS = setOf("type", "struct", "service", "component")

		/** The innermost type-like declaration around the caret, else null. */
		fun at(file: PsiFile, editor: Editor, kinds: Set<String> = TYPE_KINDS): ADMTypeAtCaret? {
			val offset = editor.caretModel.offset
			var best: ADMDeclarations.Node? = null
			fun walk(nodes: List<ADMDeclarations.Node>) {
				for (n in nodes) {
					if (offset < n.offset || offset > n.endOffset) continue
					if (n.kind == ADMDeclarations.Kind.Type && n.detail in kinds) best = n
					walk(n.children)
				}
			}
			walk(ADMDeclarations.rootsFor(file))
			return best?.let { ADMTypeAtCaret(it, editor.document) }
		}
	}
}

/** Shared shape of the Generate menu entries: enabled inside a type body of an ADM file. */
abstract class ADMGenerateAction(text: String, description: String) : DumbAwareAction(text, description, null) {
	override fun getActionUpdateThread() = ActionUpdateThread.BGT

	protected open val kinds: Set<String> = setOf("type", "struct", "service", "component")

	override fun update(e: AnActionEvent) {
		val file = e.getData(CommonDataKeys.PSI_FILE)
		val editor = e.getData(CommonDataKeys.EDITOR)
		e.presentation.isEnabledAndVisible = file != null && editor != null && file.fileType == ADMFileType.INSTANCE && ADMTypeAtCaret.at(file, editor, kinds) != null
	}

	override fun actionPerformed(e: AnActionEvent) {
		val project = e.project ?: return
		val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
		val editor = e.getData(CommonDataKeys.EDITOR) ?: return
		val type = ADMTypeAtCaret.at(file, editor, kinds) ?: return
		generate(project, editor, file, type)
	}

	abstract fun generate(project: Project, editor: Editor, file: PsiFile, type: ADMTypeAtCaret)

	/** Inserts [members] at the end of the type and puts the caret on the first inserted body line. */
	protected fun insert(project: Project, editor: Editor, file: PsiFile, type: ADMTypeAtCaret, members: List<String>, caretLineOffset: Int = 1) {
		if (members.isEmpty()) return
		WriteCommandAction.runWriteCommandAction(project, templatePresentation.text, null, {
			val at = type.append(members) ?: return@runWriteCommandAction
			PsiDocumentManager.getInstance(project).commitDocument(editor.document)
			val line = editor.document.getLineNumber(at) + caretLineOffset
			if (line < editor.document.lineCount) {
				editor.caretModel.moveToOffset(editor.document.getLineEndOffset(line))
				editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.RELATIVE)
			}
		}, file)
	}
}

/** Generate › Constructor: `def new() { }`, unless the type already has one. */
class ADMGenerateConstructorAction : ADMGenerateAction("Constructor", "Add a def new() constructor to the type at the caret") {
	override fun generate(project: Project, editor: Editor, file: PsiFile, type: ADMTypeAtCaret) {
		if ("new" in type.methodNames) {
			editor.caretModel.moveToOffset(type.node.children.first { it.name == "new" }.offset)
			return
		}
		insert(project, editor, file, type, listOf("def new() {\n\t\n}"))
	}
}

/** Generate › Destructor: `def dispose() { }`, unless the type already has one. */
class ADMGenerateDestructorAction : ADMGenerateAction("Destructor", "Add a def dispose() destructor to the type at the caret") {
	override fun generate(project: Project, editor: Editor, file: PsiFile, type: ADMTypeAtCaret) {
		if ("dispose" in type.methodNames) {
			editor.caretModel.moveToOffset(type.node.children.first { it.name == "dispose" }.offset)
			return
		}
		insert(project, editor, file, type, listOf("def dispose() {\n\t\n}"))
	}
}
