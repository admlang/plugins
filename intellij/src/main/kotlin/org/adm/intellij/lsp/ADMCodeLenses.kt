package org.adm.intellij.lsp

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Usage-count lenses from the ADM language server.
 *
 * `textDocument/codeLens` returns every declaration in the file with its
 * reference count in one request, computed off the server's own index --
 * far cheaper than one `textDocument/references` round-trip per declaration.
 */
object ADMCodeLenses {
	private val log = Logger.getInstance(ADMCodeLenses::class.java)

	/** The `adm.gotoTest` lens command: its arguments are the [TestRef]s. */
	const val GOTO_TEST = "adm.gotoTest"

	/**
	 * One lens. [command] is empty for the usage count and [GOTO_TEST] for
	 * the "tested in N tests" lens, whose [tests] come most direct first.
	 */
	data class Lens(
		val position: Position,
		val title: String,
		val command: String = "",
		val tests: List<TestRef> = emptyList(),
	)

	/** A `@test` that exercises a declaration; [label] reads `suite · test`. */
	data class TestRef(val uri: String, val line: Int, val column: Int, val label: String, val score: Int)

	/**
	 * All lenses for [uri], or null when there was no answer (no server, a
	 * failed or timed-out request) as opposed to an answer with no lenses.
	 * Runs the request synchronously, so call off the EDT.
	 */
	fun forFile(project: Project, uri: String): List<Lens>? {
		val server = ADMReferences.serverFor(project) ?: return null
		val params = CodeLensParams(TextDocumentIdentifier(uri))
		val lenses = try {
			// Explicit timeout: see ADMReferences.REQUEST_TIMEOUT_MS on why the
			// defaulted parameter must not be omitted.
			server.sendRequestSync(10_000) { ls -> ls.textDocumentService.codeLens(params) }
		} catch (t: Throwable) {
			log.warn("textDocument/codeLens failed for $uri", t)
			return null
		} ?: return null

		val out = ArrayList<Lens>(lenses.size)
		for (lens in lenses) {
			val start = lens?.range?.start ?: continue
			val command = lens.command ?: continue
			val title = command.title ?: continue
			val tests = if (command.command == GOTO_TEST) testRefs(command.arguments) else emptyList()
			out.add(Lens(start, title, command.command ?: "", tests))
		}
		return out
	}

	/** Reads the lens arguments, which arrive as untyped JSON objects. */
	private fun testRefs(arguments: List<Any?>?): List<TestRef> {
		if (arguments.isNullOrEmpty()) return emptyList()
		val out = ArrayList<TestRef>(arguments.size)
		for (arg in arguments) {
			val obj = arg as? JsonObject ?: continue
			val uri = obj.get("uri")?.asString ?: continue
			val start = obj.getAsJsonObject("range")?.getAsJsonObject("start") ?: continue
			out.add(
				TestRef(
					uri = uri,
					line = start.get("line")?.asInt ?: 0,
					column = start.get("character")?.asInt ?: 0,
					label = obj.get("label")?.asString ?: uri.substringAfterLast('/'),
					score = obj.get("score")?.asInt ?: 0,
				)
			)
		}
		return out
	}
}
