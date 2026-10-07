package org.adm.intellij.documentation

import com.intellij.lang.ASTNode
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import org.adm.intellij.ide.ADMMarkdown
import org.adm.intellij.lang.ADMTokenTypes

/**
 * Finds the doc comments of an ADM file and renders one. A doc comment is a
 * run of `//` lines, each alone on its line, directly above a declaration:
 * the same rule the compiler's resolver applies when it attaches `Doc` to a
 * symbol. The run may end in Java-style tags (`// @author Ada`,
 * `// @see Writer`, `// @since 1.2`), which render as labelled rows below
 * the description.
 */
object ADMDocComments {
	/** One doc comment: its text range, the comment text with the markers stripped, and the declaration it documents. */
	class Run(val range: TextRange, val text: String, val owner: ASTNode, val ownerRange: TextRange) {
		/** The declared name when the owner line has one, else the first token. */
		val ownerName: String
			get() {
				val line = ownerRange.substring(owner.psi.containingFile.text)
				val m = NAME_AFTER_KEYWORD.find(line)
				return m?.groupValues?.get(1) ?: owner.text
			}
	}

	/** One `@name text` tag. */
	class Tag(val name: String, val text: String)

	/** Every value of one tag name under the label it renders with. */
	class Group(val name: String, val label: String, val values: List<String>)

	/** Keywords that open a declaration, so a comment above them documents it. */
	private val DECLARATION_KEYWORDS = setOf(
		"def", "type", "struct", "enum", "union", "interface", "datatype", "service", "component",
		"const", "meta", "view", "style", "check", "static", "public",
	)
	private val NAME_AFTER_KEYWORD = Regex("""\b(?:def|type|struct|enum|union|interface|datatype|service|component|const|meta|view|style|check|module|application|plugin|library)\s+([A-Za-z_][A-Za-z0-9_.]*)""")
	private val TAG_LINE = Regex("""^@([A-Za-z][A-Za-z0-9_-]*)(?:\s+(.*))?$""")
	private val KNOWN_ORDER = mapOf("author" to 0, "see" to 1, "since" to 2)

	fun runs(file: PsiFile): List<Run> {
		val nodes = file.node.getChildren(null)
		val text = file.text
		val out = ArrayList<Run>()
		var i = 0
		while (i < nodes.size) {
			val node = nodes[i]
			if (node.elementType != ADMTokenTypes.LINE_COMMENT || !atLineStart(text, node.startOffset)) {
				i++
				continue
			}
			// The run: comments joined by whitespace holding exactly one line break.
			val comments = arrayListOf(node)
			var j = i + 1
			while (j + 1 < nodes.size && singleLineBreak(nodes[j]) && nodes[j + 1].elementType == ADMTokenTypes.LINE_COMMENT) {
				comments.add(nodes[j + 1])
				j += 2
			}
			i = j
			// What follows on the very next line.
			if (j + 1 >= nodes.size || !singleLineBreak(nodes[j])) continue
			val owner = nodes[j + 1]
			if (!opensDeclaration(owner, nodes.getOrNull(j + 2), nodes.getOrNull(j + 3))) continue
			val first = comments.first()
			val last = comments.last()
			val lineEnd = text.indexOf('\n', owner.startOffset).let { if (it < 0) text.length else it }
			out.add(
				Run(
					TextRange(first.startOffset, last.startOffset + last.textLength),
					comments.joinToString("\n") { strip(it.text) },
					owner,
					TextRange(owner.startOffset, lineEnd),
				),
			)
		}
		return out
	}

	private fun atLineStart(text: String, offset: Int): Boolean {
		var k = offset - 1
		while (k >= 0 && text[k] != '\n') {
			if (!text[k].isWhitespace()) return false
			k--
		}
		return true
	}

	private fun singleLineBreak(node: ASTNode): Boolean =
		(node.elementType == ADMTokenTypes.WHITE_SPACE || node.elementType == TokenType.WHITE_SPACE) && node.text.count { it == '\n' } == 1

	/**
	 * Whether [node] starts a declaration: a root declaration, a modifier, an
	 * annotation, a declaring keyword, or a field (`name Type`). Statements
	 * inside bodies (`let x`, `x = 1`, `f()`) do not qualify, so a comment
	 * above them stays plain.
	 */
	private fun opensDeclaration(node: ASTNode, next: ASTNode?, afterNext: ASTNode?): Boolean {
		when (node.elementType) {
			ADMTokenTypes.ROOT_DECLARATION, ADMTokenTypes.MODIFIER_PRIMARY, ADMTokenTypes.MODIFIER_FOREIGN, ADMTokenTypes.ATTRIBUTE -> return true
			ADMTokenTypes.KEYWORD -> return node.text in DECLARATION_KEYWORDS
			ADMTokenTypes.IDENTIFIER -> {
				if (next == null || afterNext == null) return false
				val space = (next.elementType == ADMTokenTypes.WHITE_SPACE || next.elementType == TokenType.WHITE_SPACE) && '\n' !in next.text
				return space && (afterNext.elementType == ADMTokenTypes.IDENTIFIER || afterNext.elementType == ADMTokenTypes.BUILTIN_TYPE)
			}
			else -> return false
		}
	}

	/** The comment text past `//` and one space, as the resolver keeps it: further indentation is a Markdown code block. */
	private fun strip(comment: String): String = comment.removePrefix("//").removePrefix(" ").trimEnd()

	/**
	 * Splits the trailing tags off a doc comment: they start at the first
	 * `@name` line after which every line is a tag, a continuation of one, or
	 * blank between two tags. An `@` line the prose resumes after is prose.
	 */
	fun splitTags(doc: String): Pair<String, List<Tag>> {
		if ('@' !in doc) return doc to emptyList()
		val lines = doc.split('\n')
		for (start in lines.indices) {
			if (!TAG_LINE.matches(lines[start].trim())) continue
			val tags = parseTags(lines.subList(start, lines.size)) ?: continue
			return lines.subList(0, start).joinToString("\n").trim() to tags
		}
		return doc to emptyList()
	}

	private fun parseTags(lines: List<String>): List<Tag>? {
		val tags = ArrayList<Tag>()
		var afterBlank = false
		for (raw in lines) {
			val line = raw.trim()
			val m = TAG_LINE.find(line)
			when {
				line.isEmpty() -> afterBlank = true
				m != null -> {
					tags.add(Tag(m.groupValues[1], m.groupValues[2].trim()))
					afterBlank = false
				}
				tags.isEmpty() || afterBlank -> return null
				else -> {
					val last = tags.removeAt(tags.size - 1)
					tags.add(Tag(last.name, if (last.text.isEmpty()) line else last.text + " " + line))
				}
			}
		}
		return tags.takeIf { it.isNotEmpty() }
	}

	/** The heading a tag renders under: the Java names spelled out, anything else capitalised. */
	fun label(name: String): String = when (name.lowercase()) {
		"author" -> "Author"
		"see" -> "See Also"
		"since" -> "Since"
		"deprecated" -> "Deprecated"
		else -> name.replaceFirstChar { it.uppercase() }
	}

	/** Tags by name, Author, See Also, Since first, the rest as they first appear. */
	fun group(tags: List<Tag>): List<Group> {
		val order = LinkedHashMap<String, MutableList<String>>()
		for (t in tags) order.getOrPut(t.name.lowercase()) { ArrayList() }.add(t.text)
		return order.entries
			.sortedBy { KNOWN_ORDER[it.key] ?: KNOWN_ORDER.size }
			.map { (name, values) -> Group(name, label(tags.first { it.name.equals(name, ignoreCase = true) }.name), values) }
	}

	/**
	 * The HTML of a rendered doc comment: the description as the hover shows
	 * it, then a row per tag group. A See Also target is a link the
	 * `adm-see://` handler resolves through the language server.
	 */
	fun render(text: String): String {
		val (description, tags) = splitTags(text)
		val b = StringBuilder()
		if (description.isNotBlank()) {
			b.append(DocumentationMarkup.CONTENT_START).append(ADMMarkdown.toHtml(description, ADMCodeHtml::spans)).append(DocumentationMarkup.CONTENT_END)
		}
		val groups = group(tags)
		if (groups.isNotEmpty()) {
			b.append(DocumentationMarkup.SECTIONS_START)
			for (g in groups) {
				b.append(DocumentationMarkup.SECTION_HEADER_START).append(esc(g.label)).append(':').append(DocumentationMarkup.SECTION_SEPARATOR)
				g.values.forEachIndexed { i, v ->
					if (i > 0) b.append(", ")
					if (g.name == "see" && v.isNotBlank()) {
						b.append("<a href='").append(ADMSeeAlsoLinkHandler.SCHEME).append(esc(v)).append("'><code>").append(esc(v)).append("</code></a>")
					} else {
						b.append(esc(v))
					}
				}
				b.append(DocumentationMarkup.SECTION_END).append("</tr>")
			}
			b.append(DocumentationMarkup.SECTIONS_END)
		}
		return b.toString()
	}

	private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
}
