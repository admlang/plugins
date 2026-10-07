package org.adm.intellij.search

import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.UsageHandler
import com.intellij.model.Pointer
import com.intellij.platform.backend.presentation.TargetPresentation
import org.adm.intellij.ADMIcons
import org.eclipse.lsp4j.Position

/**
 * A symbol to find usages of, addressed the way the language server addresses
 * it: document URI plus position. ADM has no named PSI elements to hang the
 * platform's usage search on, so this target carries the LSP coordinates and
 * [ADMUsageSearcher] resolves them through `textDocument/references`.
 */
data class ADMSearchTarget(
	val symbolName: String,
	val uri: String,
	val line: Int,
	val character: Int,
) : SearchTarget {

	val position: Position
		get() = Position(line, character)

	override fun createPointer(): Pointer<ADMSearchTarget> = Pointer.hardPointer(this)

	override fun presentation(): TargetPresentation =
		TargetPresentation.builder(symbolName).icon(ADMIcons.FILE).presentation()

	override val usageHandler: UsageHandler
		get() = UsageHandler.createEmptyUsageHandler(symbolName)
}
