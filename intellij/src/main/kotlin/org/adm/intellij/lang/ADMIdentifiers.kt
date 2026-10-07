package org.adm.intellij.lang

import com.intellij.openapi.editor.Document

/** Identifier extraction straight off document text, shared by the PSI-less features. */
object ADMIdentifiers {

	/** The identifier under [offset], or null. A caret at the end of a word still names it. */
	fun at(document: Document, offset: Int): String? {
		val text = document.charsSequence
		if (text.isEmpty()) return null
		var start = offset.coerceIn(0, text.length)
		if (start == text.length || !isIdent(text[start])) {
			if (start == 0 || !isIdent(text[start - 1])) return null
			start--
		}
		var end = start + 1
		while (start > 0 && isIdent(text[start - 1])) start--
		while (end < text.length && isIdent(text[end])) end++
		val word = text.subSequence(start, end).toString()
		return word.takeIf { it.isNotEmpty() && !it[0].isDigit() }
	}

	private fun isIdent(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
}
