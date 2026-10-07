package org.adm.intellij.documentation

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import org.adm.intellij.highlighting.ADMSyntaxHighlighter
import org.adm.intellij.lang.ADMLexer
import java.awt.Font

/**
 * ADM code as HTML spans coloured by the plugin's highlighter with the
 * editor's colour scheme, for the code blocks of rendered doc comments and
 * documentation pages. Escapes the text; the caller wraps it in `<pre>`.
 */
object ADMCodeHtml {
	private val highlighter = ADMSyntaxHighlighter()

	fun spans(code: String): String {
		val scheme = EditorColorsManager.getInstance().globalScheme
		val lexer = ADMLexer()
		lexer.start(code)
		val b = StringBuilder()
		while (lexer.tokenType != null) {
			val text = escape(code.substring(lexer.tokenStart, lexer.tokenEnd))
			val attrs = highlighter.getTokenHighlights(lexer.tokenType).firstOrNull()?.let { scheme.getAttributes(it) }
			if (attrs != null && (attrs.foregroundColor != null || attrs.fontType != Font.PLAIN)) {
				val style = StringBuilder()
				attrs.foregroundColor?.let { style.append("color:#").append(ColorUtil.toHex(it)).append(';') }
				if (attrs.fontType and Font.BOLD != 0) style.append("font-weight:bold;")
				if (attrs.fontType and Font.ITALIC != 0) style.append("font-style:italic;")
				b.append("<span style=\"").append(style).append("\">").append(text).append("</span>")
			} else {
				b.append(text)
			}
			lexer.advance()
		}
		return b.toString()
	}

	fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
