package org.adm.intellij.documentation

import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.InlineDocumentation
import com.intellij.platform.backend.documentation.InlineDocumentationProvider
import com.intellij.psi.PsiFile
import org.adm.intellij.lang.ADMLanguage

/**
 * Feeds the editor's "Render documentation comments" feature: every doc
 * comment of an ADM file becomes an inline item the platform draws as
 * formatted text, with the gutter pencil to switch back to the `//` lines.
 * The LSP keeps serving the hover card; this only covers the in-editor
 * rendering.
 */
class ADMInlineDocumentationProvider : InlineDocumentationProvider {
	override fun inlineDocumentationItems(file: PsiFile): Collection<InlineDocumentation> {
		if (file.language != ADMLanguage) return emptyList()
		return ADMDocComments.runs(file).map { ADMInlineDocumentation(file, it) }
	}

	/**
	 * The item whose comment the range covers. The platform asks with the
	 * range of its highlighter, which may have been widened to whole lines
	 * or moved by edits, so the match is by overlap, the largest one wins.
	 */
	override fun findInlineDocumentation(file: PsiFile, textRange: TextRange): InlineDocumentation? {
		if (file.language != ADMLanguage) return null
		return ADMDocComments.runs(file)
			.filter { it.range.intersects(textRange) }
			.maxByOrNull { it.range.intersection(textRange)?.length ?: 0 }
			?.let { ADMInlineDocumentation(file, it) }
	}
}

/** One rendered doc comment: its range, the declaration line below it, and the HTML. */
class ADMInlineDocumentation(private val file: PsiFile, private val run: ADMDocComments.Run) : InlineDocumentation {
	override fun getDocumentationRange(): TextRange = run.range

	override fun getDocumentationOwnerRange(): TextRange = run.ownerRange

	override fun renderText(): String = ADMDocComments.render(run.text)

	override fun getOwnerTarget(): DocumentationTarget? =
		file.virtualFile?.let { ADMDocTarget(file.project, it, run.owner.startOffset, run.ownerName) }
}
