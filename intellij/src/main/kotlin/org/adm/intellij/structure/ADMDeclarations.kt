package org.adm.intellij.structure

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import org.adm.intellij.lang.ADMLexer
import org.adm.intellij.lang.ADMTokenTypes

/**
 * Declaration tree for a `.adm` file, scanned with the plugin's own lexer.
 *
 * The ADM parser definition produces a flat PSI tree (real parsing happens in
 * the compiler, reached over LSP), so there are no PSI elements to hang a
 * structure view off. Lexing for declaration keywords and tracking brace depth
 * recovers the nesting the Structure tool window needs.
 */
object ADMDeclarations {

	enum class Kind {
		Application,
		Library,
		Plugin,
		Module,
		Type,
		Function,
		Suite,
		Field,
	}

	class Node(
		val kind: Kind,
		val name: String,
		val detail: String,
		val offset: Int,
		// Grows to the closing brace once the body is seen, so a node's range
		// covers its whole declaration and the tree can follow the caret.
		var endOffset: Int,
		val children: MutableList<Node> = ArrayList(),
	)

	private data class Token(val type: IElementType, val start: Int, val end: Int, val text: String)

	// `type`-like declarations all read the same way: keyword, then a name.
	private val TYPE_KEYWORDS = setOf(
		"type", "struct", "datatype", "enum", "union", "interface",
		"service", "component", "view", "style",
	)

	private val MODIFIERS = setOf("partial", "internal", "async", "atomic", "weak", "infix", "meta", "cuda", "sql")

	private class Cache(val stamp: Long, val roots: List<Node>)

	private val CACHE_KEY: Key<Cache> = Key.create("adm.structure.declarations")

	fun rootsFor(file: PsiFile): List<Node> {
		val stamp = file.viewProvider.modificationStamp
		file.getUserData(CACHE_KEY)?.let { if (it.stamp == stamp) return it.roots }
		val roots = compute(file.text.orEmpty())
		file.putUserData(CACHE_KEY, Cache(stamp, roots))
		return roots
	}

	fun compute(text: String): List<Node> {
		val tokens = lex(text)
		if (tokens.isEmpty()) return emptyList()

		val roots = ArrayList<Node>()
		// Declarations whose body brace is still open, innermost last, each
		// paired with the depth its body started at.
		val open = ArrayList<Pair<Node, Int>>()
		var depth = 0

		fun container(): MutableList<Node> = open.lastOrNull()?.first?.children ?: roots

		var i = 0
		while (i < tokens.size) {
			val t = tokens[i]

			when {
				t.type == ADMTokenTypes.BRACE_OPEN -> depth++

				t.type == ADMTokenTypes.BRACE_CLOSE -> {
					depth--
					// Close every declaration whose body ended here.
					while (open.isNotEmpty() && open.last().second > depth) {
						val (node, _) = open.removeAt(open.size - 1)
						node.endOffset = t.end
					}
				}

				t.type == ADMTokenTypes.ROOT_DECLARATION -> {
					val kind = when (t.text) {
						"application" -> Kind.Application
						"library" -> Kind.Library
						"plugin" -> Kind.Plugin
						else -> Kind.Module
					}
					val name = nextName(tokens, i + 1)
					if (name != null) {
						val node = Node(kind, name.text, t.text, t.start, name.end)
						container().add(node)
						open.add(node to depth + 1)
						i = name.index
					}
				}

				t.type == ADMTokenTypes.CHECK_KEYWORD -> {
					// `check "name" { ... }` -- the name is a string literal.
					val next = nextMeaningful(tokens, i + 1)
					val name = if (next != null && next.token.type == ADMTokenTypes.STRING) {
						unquote(next.token.text)
					} else {
						"check"
					}
					val node = Node(Kind.Suite, name, "check", t.start, next?.token?.end ?: t.end)
					container().add(node)
					open.add(node to depth + 1)
					if (next != null) i = next.index
				}

				t.type == ADMTokenTypes.KEYWORD && t.text == "def" -> {
					val name = nextName(tokens, i + 1)
					if (name != null) {
						val head = headAfter(tokens, name.index)
						val node = Node(Kind.Function, name.text, signatureAfter(tokens, name.index), t.start, head.end ?: name.end)
						container().add(node)
						if (head.hasBody) open.add(node to depth + 1)
						i = name.index
					}
				}

				t.type == ADMTokenTypes.KEYWORD && t.text in TYPE_KEYWORDS -> {
					// Skip `type` used as a value/annotation position: a real
					// declaration is followed by a name.
					val name = nextName(tokens, i + 1)
					if (name != null) {
						val head = headAfter(tokens, name.index)
						val node = Node(Kind.Type, name.text, t.text, t.start, head.end ?: name.end)
						container().add(node)
						if (head.hasBody) open.add(node to depth + 1)
						i = name.index
					}
				}
			}
			i++
		}

		// Anything still open ran to end of file (an unbalanced or truncated body).
		for ((node, _) in open) {
			if (node.endOffset <= node.offset) node.endOffset = text.length
		}
		return roots
	}

	private class Head(val hasBody: Boolean, val end: Int?)

	/**
	 * Whether the declaration whose name sits at [nameIndex] has a braced body,
	 * and where its header ends when it has none. A declaration without a body
	 * must not stay open, or the next declaration would nest under it: a
	 * `@dimension` unit (`type Pixels`), an alias (`type A = B | C`), a foreign
	 * `def f();` and an interface member signature all end at a line break, a
	 * `;`, a `=` or the enclosing `}` rather than at a closing brace.
	 */
	private fun headAfter(tokens: List<Token>, nameIndex: Int): Head {
		var parens = 0
		var end: Int? = null
		var i = nameIndex + 1
		while (i < tokens.size) {
			val t = tokens[i]
			when {
				t.type == ADMTokenTypes.WHITE_SPACE -> {
					if (parens == 0 && t.text.contains('\n')) {
						val next = nextMeaningful(tokens, i + 1)
						return Head(next != null && next.token.type == ADMTokenTypes.BRACE_OPEN, end)
					}
				}
				isComment(t.type) -> {}
				t.type == ADMTokenTypes.PAREN_OPEN || t.type == ADMTokenTypes.BRACKET_OPEN -> {
					parens++
					end = t.end
				}
				t.type == ADMTokenTypes.PAREN_CLOSE || t.type == ADMTokenTypes.BRACKET_CLOSE -> {
					parens--
					end = t.end
				}
				parens == 0 && t.type == ADMTokenTypes.BRACE_OPEN -> return Head(true, end)
				parens == 0 && (t.type == ADMTokenTypes.BRACE_CLOSE || t.type == ADMTokenTypes.SEMICOLON) ->
					return Head(false, if (t.type == ADMTokenTypes.SEMICOLON) t.end else end)
				parens == 0 && t.type == ADMTokenTypes.OPERATOR && t.text == "=" -> {
					// An alias: its target runs to the end of the declaration.
					return Head(false, end)
				}
				else -> end = t.end
			}
			i++
		}
		return Head(false, end)
	}

	private class Named(val text: String, val end: Int, val index: Int)

	/**
	 * The declaration name following a keyword, skipping modifiers. Returns null
	 * when no identifier follows, which is how a keyword in a non-declaring
	 * position gets ignored.
	 */
	private fun nextName(tokens: List<Token>, start: Int): Named? {
		var i = start
		while (i < tokens.size) {
			val t = tokens[i]
			if (isTrivia(t.type)) {
				i++
				continue
			}
			if (t.type == ADMTokenTypes.KEYWORD && t.text in MODIFIERS) {
				i++
				continue
			}
			// A dotted module name (`std.gfx.color`) reads as one name.
			if (t.type == ADMTokenTypes.IDENTIFIER || t.type == ADMTokenTypes.KEYWORD || t.type == ADMTokenTypes.BUILTIN_TYPE) {
				val name = StringBuilder(t.text)
				var end = t.end
				var j = i + 1
				while (j + 1 < tokens.size && tokens[j].type == ADMTokenTypes.DOT) {
					val after = tokens[j + 1]
					if (after.type != ADMTokenTypes.IDENTIFIER && after.type != ADMTokenTypes.KEYWORD) break
					name.append('.').append(after.text)
					end = after.end
					j += 2
				}
				return Named(name.toString(), end, j - 1)
			}
			return null
		}
		return null
	}

	private class Meaningful(val token: Token, val index: Int)

	private fun nextMeaningful(tokens: List<Token>, start: Int): Meaningful? {
		var i = start
		while (i < tokens.size) {
			if (!isTrivia(tokens[i].type)) return Meaningful(tokens[i], i)
			i++
		}
		return null
	}

	/**
	 * The parameter list and return type of a function, rendered for the
	 * structure view's right-hand location text. Reads verbatim from the source
	 * up to the body brace so overloads stay distinguishable.
	 */
	private fun signatureAfter(tokens: List<Token>, nameIndex: Int): String {
		val out = StringBuilder()
		var i = nameIndex + 1
		var parens = 0
		while (i < tokens.size) {
			val t = tokens[i]
			if (t.type == ADMTokenTypes.BRACE_OPEN && parens == 0) break
			if (t.type == ADMTokenTypes.PAREN_OPEN) parens++
			if (t.type == ADMTokenTypes.PAREN_CLOSE) parens--
			if (!isComment(t.type)) {
				if (t.type == ADMTokenTypes.WHITE_SPACE) {
					if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
				} else {
					out.append(t.text)
				}
			}
			i++
		}
		return out.toString().trim()
	}

	private fun isTrivia(type: IElementType): Boolean =
		type == ADMTokenTypes.WHITE_SPACE || isComment(type)

	private fun isComment(type: IElementType): Boolean =
		type == ADMTokenTypes.LINE_COMMENT || type == ADMTokenTypes.BLOCK_COMMENT

	private fun unquote(raw: String): String {
		val s = raw.trim()
		if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length - 1)
		return s
	}

	private fun lex(text: String): List<Token> {
		val lexer = ADMLexer()
		lexer.start(text)
		val out = ArrayList<Token>()
		while (lexer.tokenType != null) {
			val type = lexer.tokenType!!
			out.add(Token(type, lexer.tokenStart, lexer.tokenEnd, text.substring(lexer.tokenStart, lexer.tokenEnd)))
			lexer.advance()
		}
		return out
	}
}
