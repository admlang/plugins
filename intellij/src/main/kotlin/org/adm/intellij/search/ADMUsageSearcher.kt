package org.adm.intellij.search

import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.ReadWriteUsage
import com.intellij.find.usages.api.Usage
import com.intellij.find.usages.api.UsageAccess
import com.intellij.find.usages.api.UsageSearchParameters
import com.intellij.model.Pointer
import com.intellij.model.search.Searcher
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.adm.intellij.lsp.ADMReferences

/**
 * Serves the platform's usage search -- the Show Usages popup with its filter
 * toolbar and the Find tool window -- for [ADMSearchTarget]s.
 *
 * The platform drives searchers on a background thread, so the synchronous
 * `textDocument/references` request is at home here; the PSI conversion at the
 * end runs under a read action because usages are rendered from PSI files.
 */
class ADMUsageSearcher : Searcher<UsageSearchParameters, Usage> {
	private val log = Logger.getInstance(ADMUsageSearcher::class.java)

	override fun collectImmediateResults(parameters: UsageSearchParameters): Collection<Usage> {
		val target = parameters.target as? ADMSearchTarget ?: return emptyList()
		val project = parameters.project

		val refs = when (val result = ADMReferences.at(project, ADMReferences.Site(target.uri, target.position))) {
			is ADMReferences.Result.Found -> result.refs
			ADMReferences.Result.Empty -> emptyList()
			ADMReferences.Result.NoServer -> {
				log.warn("usage search for '${target.symbolName}': ADM language server is not running")
				emptyList()
			}
			is ADMReferences.Result.Failed -> {
				log.warn("usage search for '${target.symbolName}' failed: ${result.reason}")
				emptyList()
			}
		}
		if (refs.isEmpty()) return emptyList()

		return ReadAction.compute<Collection<Usage>, RuntimeException> {
			val psiManager = PsiManager.getInstance(project)
			val documents = FileDocumentManager.getInstance()
			refs.mapNotNull { ref ->
				val file = ref.file ?: return@mapNotNull null
				val psiFile = psiManager.findFile(file) ?: return@mapNotNull null
				val document = documents.getDocument(file) ?: return@mapNotNull null
				val start = offsetOf(document, ref.line, ref.column) ?: return@mapNotNull null
				val end = offsetOf(document, ref.endLine, ref.endColumn) ?: start
				ADMUsage(psiFile, TextRange(start, maxOf(start, end)), accessOf(ref.access))
			}
		}
	}

	private fun accessOf(kind: String?): UsageAccess? = when (kind) {
		"read" -> UsageAccess.Read
		"write" -> UsageAccess.Write
		"readwrite" -> UsageAccess.ReadWrite
		else -> null
	}

	/** A text-range usage that also answers the read/write access filters. */
	private class ADMUsage(
		private val psiFile: PsiFile,
		private val textRange: TextRange,
		private val access: UsageAccess?,
	) : PsiUsage, ReadWriteUsage {
		override fun createPointer(): Pointer<out PsiUsage> = Pointer.hardPointer(this)
		override val declaration: Boolean = false
		override val file: PsiFile get() = psiFile
		override val range: TextRange get() = textRange
		override fun computeAccess(): UsageAccess? = access
	}

	private fun offsetOf(document: Document, line: Int, character: Int): Int? {
		if (line >= document.lineCount) return null
		val offset = document.getLineStartOffset(line) + character
		return offset.takeIf { it <= document.textLength }
	}
}
