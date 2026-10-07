package org.adm.intellij.editing

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import org.adm.intellij.lang.ADMFileType

class ADMEnterHandler : EnterHandlerDelegateAdapter() {
    override fun postProcessEnter(
        file: PsiFile,
        editor: Editor,
        dataContext: DataContext,
    ): EnterHandlerDelegate.Result {
        if (file.fileType != ADMFileType.INSTANCE) return EnterHandlerDelegate.Result.Continue

        val doc = editor.document
        val caretModel = editor.caretModel
        val offset = caretModel.offset.coerceIn(0, doc.textLength)
        val line = doc.getLineNumber(offset)
        if (line <= 0) return EnterHandlerDelegate.Result.Continue

        val prevLineText = findPrevNonBlankLine(doc, line - 1) ?: ""

        val curLineStart = doc.getLineStartOffset(line)
        val curLineEnd = doc.getLineEndOffset(line)
        val curLineText = doc.getText(com.intellij.openapi.util.TextRange(curLineStart, curLineEnd))

        val baseIndent = leadingWhitespace(prevLineText)
        val shouldIndent = endsWithOpener(prevLineText)
        val shouldOutdent = startsWithCloser(curLineText)

        val indentOptions = indentOptions(file.project, file)
        val indentUnit = indentUnit(indentOptions)
        val indent = when {
            shouldOutdent -> outdent(baseIndent, indentOptions)
            shouldIndent -> baseIndent + indentUnit
            else -> baseIndent
        }

        val curIndent = leadingWhitespace(curLineText)
        doc.replaceString(curLineStart, (curLineStart + curIndent.length).coerceAtMost(doc.textLength), indent)
        caretModel.moveToOffset((curLineStart + indent.length).coerceAtMost(doc.textLength))
        return EnterHandlerDelegate.Result.Stop
    }

    private data class IndentOptions(val indentSize: Int, val useTabs: Boolean, val tabSize: Int)

    private fun indentOptions(project: Project?, file: PsiFile): IndentOptions {
        if (project == null) return IndentOptions(indentSize = 4, useTabs = false, tabSize = 4)
        val settings = CodeStyleSettingsManager.getInstance(project).currentSettings
        val opts = settings.getIndentOptions(file.fileType)
        val indentSize = opts.INDENT_SIZE.takeIf { it > 0 } ?: 4
        val tabSize = opts.TAB_SIZE.takeIf { it > 0 } ?: indentSize
        return IndentOptions(indentSize = indentSize, useTabs = opts.USE_TAB_CHARACTER, tabSize = tabSize)
    }

    private fun indentUnit(opts: IndentOptions): String {
        return if (opts.useTabs) "\t" else " ".repeat(opts.indentSize)
    }

    private fun outdent(baseIndent: String, opts: IndentOptions): String {
        if (baseIndent.isEmpty()) return baseIndent
        if (opts.useTabs && baseIndent.endsWith("\t")) {
            return baseIndent.dropLast(1)
        }
        val cut = opts.indentSize
        if (baseIndent.length >= cut && baseIndent.takeLast(cut).all { it == ' ' }) {
            return baseIndent.dropLast(cut)
        }
        if (baseIndent.endsWith(' ')) {
            return baseIndent.dropLast(1)
        }
        if (baseIndent.endsWith('\t')) {
            return baseIndent.dropLast(1)
        }
        return baseIndent
    }

    private fun findPrevNonBlankLine(doc: com.intellij.openapi.editor.Document, startLine: Int): String? {
        var l = startLine
        while (l >= 0) {
            val s = doc.getLineStartOffset(l)
            val e = doc.getLineEndOffset(l)
            val text = doc.getText(com.intellij.openapi.util.TextRange(s, e))
            if (text.isNotBlank()) return text
            l--
        }
        return null
    }

    private fun leadingWhitespace(line: String): String {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return line.substring(0, i)
    }

    private fun endsWithOpener(line: String): Boolean {
        val trimmed = line.trimEnd()
        if (trimmed.isEmpty()) return false
        val last = trimmed.last()
        if (last == '{' || last == '(' || last == '[') return true
        if (last == ':' && (trimmed.startsWith("case ") || trimmed == "case" || trimmed == "default:" || trimmed == "default")) return true
        return false
    }

    private fun startsWithCloser(line: String): Boolean {
        val trimmed = line.trimStart()
        if (trimmed.isEmpty()) return false
        val first = trimmed.first()
        return first == '}' || first == ')' || first == ']'
    }
}
