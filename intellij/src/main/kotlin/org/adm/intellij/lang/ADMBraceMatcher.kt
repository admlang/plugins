package org.adm.intellij.lang

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class ADMBraceMatcher : PairedBraceMatcher {
    private val pairs = arrayOf(
        BracePair(ADMTokenTypes.BRACE_OPEN, ADMTokenTypes.BRACE_CLOSE, true),
        BracePair(ADMTokenTypes.BRACKET_OPEN, ADMTokenTypes.BRACKET_CLOSE, false),
        BracePair(ADMTokenTypes.PAREN_OPEN, ADMTokenTypes.PAREN_CLOSE, false)
    )

    override fun getPairs(): Array<BracePair> = pairs

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean {
        return true
    }

    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset
}
