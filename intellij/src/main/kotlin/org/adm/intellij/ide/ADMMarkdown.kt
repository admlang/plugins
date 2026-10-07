package org.adm.intellij.ide

/**
 * The Markdown subset doc comments use, rendered to the HTML a JEditorPane
 * understands: paragraphs, ATX headings, bullet and numbered lists, fenced
 * and indented code blocks, inline code, emphasis and links. The IDE's own
 * Markdown parser lives in a JetBrains-internal module that third-party
 * plugins cannot depend on, so this stays self-contained.
 */
object ADMMarkdown {
	/**
	 * [code] renders the body of a code block (fenced or indented) to HTML,
	 * escaped; the default keeps it plain. Blocks are wrapped the way the
	 * platform's documentation panes expect (`div.code-block`), which is
	 * what draws the grey box behind them.
	 */
	fun toHtml(text: String, code: (String) -> String = ::escape): String {
		val codeBlock = { body: String -> "<div class=\"code-block\"><pre>" + code(body) + "</pre></div>" }
		val out = StringBuilder()
		val lines = text.trim().lines()
		var i = 0
		val para = StringBuilder()
		fun flushPara() {
			if (para.isNotEmpty()) {
				out.append("<p>").append(inline(para.toString().trim())).append("</p>")
				para.setLength(0)
			}
		}
		while (i < lines.size) {
			val line = lines[i]
			val trimmed = line.trim()
			when {
				trimmed.startsWith("```") -> {
					flushPara()
					val code = StringBuilder()
					i++
					while (i < lines.size && !lines[i].trim().startsWith("```")) {
						code.append(lines[i]).append('\n')
						i++
					}
					out.append(codeBlock(code.toString().trimEnd()))
					i++
				}
				trimmed.isEmpty() -> {
					flushPara()
					i++
				}
				Regex("^#{1,6}\\s+").containsMatchIn(trimmed) -> {
					flushPara()
					val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(4) + 1
					out.append("<h$level>").append(inline(trimmed.dropWhile { it == '#' }.trim())).append("</h$level>")
					i++
				}
				Regex("^([-*+]|\\d+[.)])\\s+").containsMatchIn(trimmed) -> {
					flushPara()
					val ordered = trimmed[0].isDigit()
					out.append(if (ordered) "<ol>" else "<ul>")
					while (i < lines.size) {
						val t = lines[i].trim()
						val m = Regex("^([-*+]|\\d+[.)])\\s+(.*)$").find(t) ?: break
						val item = StringBuilder(m.groupValues[2])
						i++
						// Continuation lines belong to the item until a blank line or the next marker.
						while (i < lines.size && lines[i].trim().isNotEmpty() && !Regex("^([-*+]|\\d+[.)])\\s+").containsMatchIn(lines[i].trim())) {
							item.append(' ').append(lines[i].trim())
							i++
						}
						out.append("<li>").append(inline(item.toString())).append("</li>")
					}
					out.append(if (ordered) "</ol>" else "</ul>")
				}
				line.startsWith("    ") || line.startsWith("\t") -> {
					flushPara()
					val code = StringBuilder()
					while (i < lines.size && (lines[i].startsWith("    ") || lines[i].startsWith("\t") || lines[i].isBlank())) {
						code.append(lines[i].removePrefix("    ").removePrefix("\t")).append('\n')
						i++
					}
					out.append(codeBlock(code.toString().trimEnd()))
				}
				else -> {
					if (para.isNotEmpty()) para.append(' ')
					para.append(trimmed)
					i++
				}
			}
		}
		flushPara()
		return out.toString()
	}

	/** Inline code, bold, italics and links; everything else escaped. */
	private fun inline(text: String): String {
		val parts = text.split('`')
		val b = StringBuilder()
		for ((k, part) in parts.withIndex()) {
			if (k % 2 == 1 && k < parts.size - 1) {
				b.append("<code>").append(escape(part)).append("</code>")
			} else {
				var s = escape(if (k % 2 == 1) "`$part" else part)
				s = s.replace(Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)"), "<a href=\"$2\">$1</a>")
				s = s.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
				s = s.replace(Regex("(?<![\\w*])\\*([^*]+)\\*(?![\\w*])"), "<i>$1</i>")
				s = s.replace(Regex("(?<![\\w_])_([^_]+)_(?![\\w_])"), "<i>$1</i>")
				b.append(s)
			}
		}
		return b.toString()
	}

	fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
