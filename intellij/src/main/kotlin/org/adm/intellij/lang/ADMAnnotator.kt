
package org.adm.intellij.lang

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.adm.intellij.highlighting.ADMHighlighterColors

class ADMAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val type = element.node.elementType
        val text = element.text

        // Handle check blocks by detecting the pattern when we see a CHECK_KEYWORD
        if (type == ADMTokenTypes.CHECK_KEYWORD) {
            highlightCheckBlock(element, holder)
        }

        // Handle deprecated keywords
        if (type == ADMTokenTypes.KEYWORD && text == "start") {
            holder.newAnnotation(
                HighlightSeverity.WARNING,
                "`start` is deprecated; use `begin`"
            ).range(element).create()
            return
        }

        // Handle special identifier cases
        if (type == ADMTokenTypes.IDENTIFIER) {
            when {
                isUsePathIdentifier(element) || isQualifiedTypePrefix(element) ->
                    colorize(holder, element, ADMHighlighterColors.MODULE_IDENTIFIER)
                isConstantIdentifier(text) ->
                    colorize(holder, element, ADMHighlighterColors.CONSTANT_IDENTIFIER)
            }
        }

        // Handle specific keyword types that need additional highlighting
        when (type) {
            ADMTokenTypes.ROOT_DECLARATION -> {
                colorize(holder, element, ADMHighlighterColors.ROOT_DECLARATION)
            }
            ADMTokenTypes.MODIFIER_PRIMARY -> {
                colorize(holder, element, ADMHighlighterColors.MODIFIER_PRIMARY)
            }
            ADMTokenTypes.MODIFIER_FOREIGN -> {
                colorize(holder, element, ADMHighlighterColors.MODIFIER_FOREIGN)
            }
            ADMTokenTypes.TRANSACTION_KEYWORD -> {
                colorize(holder, element, ADMHighlighterColors.TRANSACTION_KEYWORD)
            }
            ADMTokenTypes.GUARD_KEYWORD -> {
                colorize(holder, element, ADMHighlighterColors.GUARD_KEYWORD)
            }
            ADMTokenTypes.CHECK_KEYWORD -> {
                colorize(holder, element, ADMHighlighterColors.CHECK_KEYWORD)
            }
            ADMTokenTypes.ONERROR_KEYWORD -> {
                colorize(holder, element, ADMHighlighterColors.ONERROR_KEYWORD)
            }
            ADMTokenTypes.FAIL_KEYWORD -> {
                colorize(holder, element, ADMHighlighterColors.FAIL_KEYWORD)
            }
            ADMTokenTypes.KEYWORD -> {
                colorize(holder, element, ADMHighlighterColors.KEYWORD)
            }
        }
    }

    private fun highlightCheckBlock(checkKeyword: PsiElement, holder: AnnotationHolder) {
        // Find the block that follows this check keyword
        var current = checkKeyword.nextSibling
        
        // Skip whitespace and string (check name)
        while (current != null) {
            when (current.node.elementType) {
                ADMTokenTypes.WHITE_SPACE -> {
                    current = current.nextSibling
                    continue
                }
                ADMTokenTypes.STRING -> {
                    current = current.nextSibling
                    continue
                }
                ADMTokenTypes.BRACE_OPEN -> {
                    // Found the opening brace, now find the matching closing brace
                    val blockRange = findBlockRange(current)
                    if (blockRange != null) {
                        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                            .range(blockRange)
                            .textAttributes(ADMHighlighterColors.CHECK_BLOCK)
                            .create()
                    }
                    return
                }
                else -> return // No block found
            }
        }
    }

    private fun findBlockRange(openBrace: PsiElement): TextRange? {
        var current = openBrace.nextSibling
        var braceCount = 1
        
        while (current != null && braceCount > 0) {
            when (current.node.elementType) {
                ADMTokenTypes.BRACE_OPEN -> braceCount++
                ADMTokenTypes.BRACE_CLOSE -> {
                    braceCount--
                    if (braceCount == 0) {
                        // Found the matching closing brace
                        return TextRange(
                            openBrace.textRange.startOffset,
                            current.textRange.endOffset
                        )
                    }
                }
            }
            current = current.nextSibling
        }
        
        return null // No matching brace found
    }

    private fun colorize(holder: AnnotationHolder, element: PsiElement, key: TextAttributesKey) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element)
            .textAttributes(key)
            .create()
    }

    private fun isConstantIdentifier(text: String): Boolean {
        if (text.length < 2) return false
        var hasLetter = false
        for (ch in text) {
            if (ch.isLetter()) {
                hasLetter = true
                if (!ch.isUpperCase()) return false
            } else if (ch != '_' && !ch.isDigit()) {
                return false
            }
        }
        return hasLetter
    }

    private fun isUsePathIdentifier(element: PsiElement): Boolean {
        var current: PsiElement? = element.prevSibling
        while (current != null) {
            val type = current.node.elementType
            when (type) {
                ADMTokenTypes.WHITE_SPACE -> if (current.textContains('\n')) return false
                ADMTokenTypes.COMMA, ADMTokenTypes.SEMICOLON -> return false
                ADMTokenTypes.KEYWORD -> {
                    val keyword = current.text
                    if (keyword == "use") return true
                    if (keyword == "as") return false
                }
            }
            current = current.prevSibling
        }
        return false
    }

    private fun isQualifiedTypePrefix(element: PsiElement): Boolean {
        val next = nextSignificantSibling(element) ?: return false
        if (next.node.elementType != ADMTokenTypes.DOT) return false
        val afterDot = nextSignificantSibling(next) ?: return false
        if (afterDot.node.elementType != ADMTokenTypes.IDENTIFIER) return false
        val identifier = afterDot.text
        return identifier.isNotEmpty() && identifier[0].isUpperCase()
    }

    private fun nextSignificantSibling(element: PsiElement): PsiElement? {
        var next: PsiElement? = element.nextSibling
        while (next != null) {
            val type = next.node.elementType
            if (type == ADMTokenTypes.WHITE_SPACE) {
                if (next.textContains('\n')) {
                    return null
                }
                next = next.nextSibling
                continue
            }
            return next
        }
        return null
    }
}
