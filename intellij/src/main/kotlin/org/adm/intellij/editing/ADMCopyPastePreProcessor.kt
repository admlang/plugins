package org.adm.intellij.editing

import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.openapi.editor.RawText;
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.adm.intellij.lang.ADMFileType

class ADMCopyPastePreProcessor : CopyPastePreProcessor {
    override fun preprocessOnCopy(file: PsiFile?, startOffsets: IntArray?, endOffsets: IntArray?, text: String?): String? {
        return text
    }

    override fun preprocessOnPaste(project: Project, file: PsiFile, editor: Editor, text: String, rawText: RawText): String {
        if (file.fileType != ADMFileType.INSTANCE) return text
        if (text.isBlank()) return text

        val doc = editor.document
        val offset = editor.caretModel.offset.coerceIn(0, doc.textLength)
        val line = doc.getLineNumber(offset)
        val lineStart = doc.getLineStartOffset(line)
        val lineEnd = doc.getLineEndOffset(line)
        val lineText = doc.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))
        val baseIndent = leadingWhitespace(lineText)

        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        if (lines.size <= 1) return text

        val minIndent = lines
            .drop(1)
            .filter { it.isNotBlank() }
            .minOfOrNull { leadingWhitespace(it).length } ?: 0

        val out = ArrayList<String>(lines.size)
        out.add(lines[0])
        for (i in 1 until lines.size) {
            val l = lines[i]
            if (l.isBlank()) {
                out.add("")
                continue
            }
            val stripped = if (l.length >= minIndent) l.drop(minIndent) else l
            out.add(baseIndent + stripped)
        }
        return out.joinToString("\n")
    }

    private fun leadingWhitespace(line: String): String {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return line.substring(0, i)
    }
}
