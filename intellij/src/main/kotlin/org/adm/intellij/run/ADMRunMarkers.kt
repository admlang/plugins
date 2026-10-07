package org.adm.intellij.run

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import org.adm.intellij.lang.ADMLexer
import org.adm.intellij.lang.ADMTokenTypes

object ADMRunMarkers {
	enum class Kind { Test, Benchmark, Suite, Application, Plugin, Library }

	data class Marker(
		val kind: Kind,
		val offset: Int,
		val title: String,
		val args: List<String>,
	)

	private data class Token(val type: IElementType, val start: Int, val end: Int, val text: String)

	private enum class ContextKind { Block, Application, Suite }
	private data class Context(val kind: ContextKind, val name: String?)

	private data class MarkerCache(val stamp: Long, val markers: Map<Int, Marker>)
	private val MARKERS_KEY: Key<MarkerCache> = Key.create("adm.run.markers.cache")

	fun markerAt(file: PsiFile, offset: Int): Marker? {
		return markersFor(file)[offset]
	}

	private fun markersFor(file: PsiFile): Map<Int, Marker> {
		val stamp = file.viewProvider.modificationStamp
		val cached = file.getUserData(MARKERS_KEY)
		if (cached != null && cached.stamp == stamp) return cached.markers
		val markers = computeMarkers(file).associateBy { it.offset }
		file.putUserData(MARKERS_KEY, MarkerCache(stamp, markers))
		return markers
	}

	private fun computeMarkers(file: PsiFile): List<Marker> {
		val text = file.text ?: return emptyList()
		val vf = file.virtualFile ?: return emptyList()
		val filePath = vf.path

		val tokens = lex(text)
		if (tokens.isEmpty()) return emptyList()

		val markers = ArrayList<Marker>()

		val stack = ArrayList<Context>()
		var pendingContext: Context? = null
		var pendingAppName: String? = null
		var lastTestAttribute = false
		var lastBenchmarkAttribute = false

		fun topApplicationName(): String? {
			for (i in stack.size - 1 downTo 0) {
				val ctx = stack[i]
				if (ctx.kind == ContextKind.Application) return ctx.name
			}
			return null
		}

		var i = 0
		while (i < tokens.size) {
			val t = tokens[i]
			val type = t.type

			if (type == ADMTokenTypes.ROOT_DECLARATION && t.text == "application") {
				val appNameTok = nextNonTrivia(tokens, i + 1)
				val appName = appNameTok?.takeIf { it.type == ADMTokenTypes.IDENTIFIER }?.text
				if (appName != null) {
					val runPath = runPathFor(vf.parent.path, projectBasePath = file.project.basePath)
					markers.add(
						Marker(
							kind = Kind.Application,
							offset = t.start,
							title = "ADM: run $appName",
							args = listOf("run", appName, "--path", runPath),
						)
					)
				}
				pendingAppName = null
				pendingContext = Context(ContextKind.Application, null)
				lastTestAttribute = false
				i++
				continue
			}

			// A plugin unit builds to an .admplugin container; the marker runs
			// `adm build <Name>` from the file's directory, like an application's
			// marker runs `adm run <Name>`.
			if (type == ADMTokenTypes.ROOT_DECLARATION && t.text == "plugin") {
				val nameTok = nextNonTrivia(tokens, i + 1)
				val pluginName = nameTok?.takeIf { it.type == ADMTokenTypes.IDENTIFIER }?.text
				if (pluginName != null) {
					val runPath = runPathFor(vf.parent.path, projectBasePath = file.project.basePath)
					markers.add(
						Marker(
							kind = Kind.Plugin,
							offset = t.start,
							title = "ADM: build $pluginName",
							args = listOf("build", pluginName, "--path", runPath),
						)
					)
				}
				pendingContext = Context(ContextKind.Block, null)
				lastTestAttribute = false
				i++
				continue
			}

			// A library unit builds to a `<name>-<version>.admlib` archive the same
			// way; its name is dotted (`library acme.imaging`).
			if (type == ADMTokenTypes.ROOT_DECLARATION && t.text == "library") {
				val libraryName = dottedNameAfter(tokens, i + 1)
				if (libraryName != null) {
					val runPath = runPathFor(vf.parent.path, projectBasePath = file.project.basePath)
					markers.add(
						Marker(
							kind = Kind.Library,
							offset = t.start,
							title = "ADM: build $libraryName",
							args = listOf("build", libraryName, "--path", runPath),
						)
					)
				}
				pendingContext = Context(ContextKind.Block, null)
				lastTestAttribute = false
				i++
				continue
			}

			if (pendingContext?.kind == ContextKind.Application && pendingAppName == null && type == ADMTokenTypes.IDENTIFIER) {
				pendingAppName = t.text
				pendingContext = Context(ContextKind.Application, pendingAppName)
				i++
				continue
			}

			if (type == ADMTokenTypes.CHECK_KEYWORD && t.text == "check") {
				val suiteName = parseCheckName(tokens, i)
				if (suiteName != null) {
					pendingContext = Context(ContextKind.Suite, suiteName)
					markers.add(
						Marker(
							kind = Kind.Suite,
							offset = t.start,
							title = "ADM: test suite $suiteName",
							args = listOf("test", filePath, "--no-cache", "--run", "check(\"$suiteName\")", "--file", "=$filePath"),
						)
					)
				} else {
					pendingContext = Context(ContextKind.Suite, null)
				}
				lastTestAttribute = false
				lastBenchmarkAttribute = false
				i++
				continue
			}

			if (type == ADMTokenTypes.ATTRIBUTE) {
				val attr = t.text.trim()
				lastTestAttribute = attr == "@test" || attr == "@only"
				lastBenchmarkAttribute = attr == "@benchmark"
				i++
				continue
			}

			if (type == ADMTokenTypes.KEYWORD && t.text == "def") {
				val nameTok = nextNonTrivia(tokens, i + 1)
				val fnName = nameTok
					?.takeIf { it.type == ADMTokenTypes.IDENTIFIER || it.type == ADMTokenTypes.KEYWORD }
					?.text
				if (fnName != null) {
					if (fnName == "new") {
						val appName = topApplicationName()
						if (appName != null) {
							val runPath = runPathFor(vf.parent.path, projectBasePath = file.project.basePath)
							markers.add(
								Marker(
									kind = Kind.Application,
									offset = nameTok.start,
									title = "ADM: run $appName",
									args = listOf("run", appName, "--path", runPath),
								)
							)
						}
					} else if (lastTestAttribute) {
						markers.add(
							Marker(
								kind = Kind.Test,
								offset = nameTok.start,
								title = "ADM: test $fnName",
								args = listOf("test", filePath, "--no-cache", "--run", fnName, "--file", "=$filePath"),
							)
						)
					} else if (lastBenchmarkAttribute) {
						markers.add(
							Marker(
								kind = Kind.Benchmark,
								offset = nameTok.start,
								title = "ADM: bench $fnName",
								args = listOf("test", filePath, "--no-cache", "--bench", fnName, "--file", "=$filePath"),
							)
						)
					} else if (stack.any { it.kind == ContextKind.Suite } && fnName == "run") {
						markers.add(
							Marker(
								kind = Kind.Test,
								offset = nameTok.start,
								title = "ADM: test $fnName",
								args = listOf("test", filePath, "--no-cache", "--run", fnName, "--file", "=$filePath"),
							)
						)
					}
				}
				lastTestAttribute = false
				lastBenchmarkAttribute = false
				i++
				continue
			}

			if (type == ADMTokenTypes.BRACE_OPEN) {
				stack.add(pendingContext ?: Context(ContextKind.Block, null))
				pendingContext = null
				pendingAppName = null
				lastTestAttribute = false
				lastBenchmarkAttribute = false
				i++
				continue
			}

			if (type == ADMTokenTypes.BRACE_CLOSE) {
				if (stack.isNotEmpty()) {
					stack.removeAt(stack.size - 1)
				}
				pendingContext = null
				pendingAppName = null
				lastTestAttribute = false
				lastBenchmarkAttribute = false
				i++
				continue
			}

			i++
		}

		return markers
	}

	private fun runPathFor(fileDir: String, projectBasePath: String?): String {
		val normalized = fileDir.replace('\\', '/')
		val marker = "/testdata/apps/"
		val idx = normalized.indexOf(marker)
		if (idx >= 0) {
			val after = normalized.substring(idx + marker.length)
			val app = after.substringBefore('/')
			if (app.isNotBlank()) {
				return normalized.substring(0, idx + marker.length) + app
			}
		}
		// Outside `testdata/apps/*`, prefer the directory that contains the file.
		// Relying on the project base path breaks ad-hoc apps such as
		// `testdata/sandbox/*.adm`.
		return fileDir
	}

	private fun parseCheckName(tokens: List<Token>, checkIndex: Int): String? {
		val after = nextNonTriviaWithIndex(tokens, checkIndex + 1) ?: return null
		return when {
			after.first.type == ADMTokenTypes.PAREN_OPEN -> {
				val str = nextNonTriviaWithIndex(tokens, after.second + 1) ?: return null
				if (str.first.type != ADMTokenTypes.STRING) return null
				unquote(str.first.text)
			}
			after.first.type == ADMTokenTypes.STRING -> unquote(after.first.text)
			else -> null
		}
	}

	/** The dotted name (`acme.imaging`) starting at the next non-trivia token, or null. */
	private fun dottedNameAfter(tokens: List<Token>, start: Int): String? {
		val (first, index) = nextNonTriviaWithIndex(tokens, start) ?: return null
		if (first.type != ADMTokenTypes.IDENTIFIER) return null
		val name = StringBuilder(first.text)
		var j = index + 1
		while (j + 1 < tokens.size && tokens[j].type == ADMTokenTypes.DOT && tokens[j + 1].type == ADMTokenTypes.IDENTIFIER) {
			name.append('.').append(tokens[j + 1].text)
			j += 2
		}
		return name.toString()
	}

	private fun nextNonTrivia(tokens: List<Token>, start: Int): Token? {
		var i = start
		while (i < tokens.size) {
			val t = tokens[i]
			if (t.type != ADMTokenTypes.WHITE_SPACE && t.type != ADMTokenTypes.LINE_COMMENT && t.type != ADMTokenTypes.BLOCK_COMMENT) {
				return t
			}
			i++
		}
		return null
	}

	private fun nextNonTriviaWithIndex(tokens: List<Token>, start: Int): Pair<Token, Int>? {
		var i = start
		while (i < tokens.size) {
			val t = tokens[i]
			if (t.type != ADMTokenTypes.WHITE_SPACE && t.type != ADMTokenTypes.LINE_COMMENT && t.type != ADMTokenTypes.BLOCK_COMMENT) {
				return t to i
			}
			i++
		}
		return null
	}

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
			val start = lexer.tokenStart
			val end = lexer.tokenEnd
			val tokText = text.substring(start, end)
			out.add(Token(type, start, end, tokText))
			lexer.advance()
		}
		return out
	}
}
