package org.adm.intellij.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.adm.intellij.lang.ADMTokenTypes.BLOCK_COMMENT
import org.adm.intellij.lang.ADMTokenTypes.BRACE_CLOSE
import org.adm.intellij.lang.ADMTokenTypes.BRACE_OPEN
import org.adm.intellij.lang.ADMTokenTypes.BRACKET_CLOSE
import org.adm.intellij.lang.ADMTokenTypes.BRACKET_OPEN
import org.adm.intellij.lang.ADMTokenTypes.PAREN_CLOSE
import org.adm.intellij.lang.ADMTokenTypes.PAREN_OPEN
import org.adm.intellij.lang.ADMTokenTypes.STRING
import java.util.ArrayDeque

class ADMFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(
        root: PsiElement,
        document: Document,
        quick: Boolean
    ): Array<FoldingDescriptor> {
        val descriptors = mutableListOf<FoldingDescriptor>()
        root.node?.let { collectFoldingRegions(it, document, descriptors) }
        return descriptors.toTypedArray()
    }

    override fun buildFoldRegions(
        root: ASTNode,
        document: Document
    ): Array<FoldingDescriptor> {
        val descriptors = mutableListOf<FoldingDescriptor>()
        collectFoldingRegions(root, document, descriptors)
        return descriptors.toTypedArray()
    }

    private fun collectFoldingRegions(
        root: ASTNode,
        document: Document,
        descriptors: MutableList<FoldingDescriptor>
    ) {
        val braceStack = ArrayDeque<ASTNode>()
        val bracketStack = ArrayDeque<ASTNode>()
        val parenStack = ArrayDeque<ASTNode>()

        fun popAndFold(open: ASTNode?, close: ASTNode?) {
            if (open == null || close == null) return
            val range = TextRange(open.textRange.startOffset, close.textRange.endOffset)
            if (!isMultiline(document, range)) return
            if (range.length <= 2) return
            descriptors.add(FoldingDescriptor(open, range))
        }

        fun walk(node: ASTNode) {
            val elementType = node.elementType
            when (elementType) {
                BLOCK_COMMENT, STRING -> {
                    val range = node.textRange
                    if (isMultiline(document, range)) {
                        descriptors.add(FoldingDescriptor(node, range))
                    }
                }

                BRACE_OPEN -> braceStack.addLast(node)
                BRACE_CLOSE -> popAndFold(braceStack.removeLastOrNull(), node)

                BRACKET_OPEN -> bracketStack.addLast(node)
                BRACKET_CLOSE -> popAndFold(bracketStack.removeLastOrNull(), node)

                PAREN_OPEN -> parenStack.addLast(node)
                PAREN_CLOSE -> popAndFold(parenStack.removeLastOrNull(), node)
            }

            for (child in node.getChildren(null)) {
                walk(child)
            }
        }

        walk(root)
    }

    private fun isMultiline(document: Document, range: TextRange): Boolean {
        val startLine = document.getLineNumber(range.startOffset)
        val endLine = document.getLineNumber(range.endOffset)
        return endLine > startLine
    }

    override fun getPlaceholderText(node: ASTNode): String {
        return when (node.elementType) {
            BLOCK_COMMENT -> "/* ... */"
            STRING -> "\"...\""
            BRACKET_OPEN -> "[...]"
            PAREN_OPEN -> "(...)"
            else -> "{...}"
        }
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}

private fun <T> ArrayDeque<T>.removeLastOrNull(): T? {
    return if (isEmpty()) null else removeLast()
}
