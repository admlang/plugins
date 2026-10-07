package org.adm.intellij.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

class ADMCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    if (isAfterMemberAccess(parameters)) return
                    if (currentPrefix(parameters).isEmpty()) return
                    provideKeywords(result)
                }
            }
        )
    }

    private fun isAfterMemberAccess(parameters: CompletionParameters): Boolean {
        val editor = parameters.editor
        val offset = parameters.offset
        val text = editor.document.charsSequence
        var i = offset - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        while (i >= 0 && isIdentifierPart(text[i])) i--
        while (i >= 0 && text[i].isWhitespace()) i--
        return i >= 0 && text[i] == '.'
    }

    private fun currentPrefix(parameters: CompletionParameters): String {
        val editor = parameters.editor
        val offset = parameters.offset
        val text = editor.document.charsSequence
        var i = offset - 1
        while (i >= 0 && isIdentifierPart(text[i])) i--
        val start = i + 1
        if (start < 0 || start > offset) return ""
        return text.subSequence(start, offset).toString()
    }

    private fun isIdentifierPart(ch: Char): Boolean {
        return ch == '_' || ch.isLetterOrDigit()
    }

    private fun provideKeywords(result: CompletionResultSet) {
        ADMLanguageData.KEYWORDS.forEach {
            result.addElement(
                LookupElementBuilder.create(it)
                    .withBoldness(true)
                    .withTypeText("keyword", true)
            )
        }
        ADMLanguageData.BUILTIN_TYPES.forEach {
            result.addElement(
                LookupElementBuilder.create(it)
                    .withTypeText("type", true)
            )
        }
    }
}
