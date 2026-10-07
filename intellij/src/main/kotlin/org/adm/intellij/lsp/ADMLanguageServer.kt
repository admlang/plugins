package org.adm.intellij.lsp

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/**
 * The ADM language server's protocol: LSP plus the ADM-specific (adm/...) requests the
 * editor features need beyond it. Installed through
 * [ADMLspServerDescriptor.lsp4jServerClass] so the platform's proxy answers
 * these alongside the standard methods.
 */
interface ADMLanguageServer : LanguageServer {

	/** `textDocument/references` with the access kind attached to each site. */
	@JsonRequest("adm/references")
	fun admReferences(params: ReferenceParams): CompletableFuture<List<ADMReference>?>

	/** Every declaration in a document that implements an interface, with the interfaces. */
	@JsonRequest("adm/implements")
	fun admImplements(params: ADMImplementsParams): CompletableFuture<List<ADMImplementsEntry>?>

	/** The lint findings and compiler errors of the files under a directory, from the server's current analysis. */
	@JsonRequest("adm/lint")
	fun admLint(params: ADMLintParams): CompletableFuture<ADMLintResult?>
}

class ADMLintParams(var dir: String? = null)

class ADMLintResult {
	var findings: List<ADMLintFinding>? = null
	var errors: List<ADMLintFinding>? = null
	var generation: Long = 0
}

class ADMLintFinding {
	var file: String? = null
	var line: Int = 1
	var column: Int = 1
	var code: String? = null
	var severity: String? = null
	var message: String? = null
	var fix: String? = null
	var edits: List<ADMLintEdit>? = null
}

class ADMLintEdit {
	var file: String? = null
	var line: Int = 1
	var column: Int = 1
	var endLine: Int = 1
	var endColumn: Int = 1
	var text: String? = null
}

/** Gson-bound payloads; mutable no-arg shapes so lsp4j can deserialize them. */
class ADMReference {
	var uri: String? = null
	var range: Range? = null
	/** "read", "write", or "readwrite". */
	var access: String? = null
}

class ADMImplementsParams(var textDocument: TextDocumentIdentifier? = null)

class ADMImplementsEntry {
	/** The declaration's name. */
	var range: Range? = null
	/** "implements" (on types) or "implementedBy" (on interfaces and datatypes). */
	var kind: String? = null
	var targets: List<ADMImplementsTarget>? = null
}

class ADMImplementsTarget {
	var name: String? = null
	var location: Location? = null
}
