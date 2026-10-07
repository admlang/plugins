package org.adm.intellij.highlighting

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.adm.intellij.lang.ADMLexer
import org.adm.intellij.lang.ADMTokenTypes

class ADMSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = ADMLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        val key = when (tokenType) {
            ADMTokenTypes.KEYWORD -> ADMHighlighterColors.KEYWORD
            ADMTokenTypes.ROOT_DECLARATION -> ADMHighlighterColors.ROOT_DECLARATION
            ADMTokenTypes.MODIFIER_PRIMARY -> ADMHighlighterColors.MODIFIER_PRIMARY
            ADMTokenTypes.MODIFIER_FOREIGN -> ADMHighlighterColors.MODIFIER_FOREIGN
            ADMTokenTypes.TRANSACTION_KEYWORD -> ADMHighlighterColors.TRANSACTION_KEYWORD
            ADMTokenTypes.GUARD_KEYWORD -> ADMHighlighterColors.GUARD_KEYWORD
            ADMTokenTypes.CHECK_KEYWORD -> ADMHighlighterColors.CHECK_KEYWORD
            ADMTokenTypes.ONERROR_KEYWORD -> ADMHighlighterColors.ONERROR_KEYWORD
            ADMTokenTypes.FAIL_KEYWORD -> ADMHighlighterColors.FAIL_KEYWORD
            ADMTokenTypes.BUILTIN_TYPE -> ADMHighlighterColors.BUILTIN_TYPE
            ADMTokenTypes.IDENTIFIER -> ADMHighlighterColors.IDENTIFIER
            ADMTokenTypes.NUMBER -> ADMHighlighterColors.NUMBER
            ADMTokenTypes.STRING -> ADMHighlighterColors.STRING
            ADMTokenTypes.CHAR -> ADMHighlighterColors.CHAR
            ADMTokenTypes.REGEX -> ADMHighlighterColors.REGEX
            ADMTokenTypes.LINE_COMMENT -> ADMHighlighterColors.LINE_COMMENT
            ADMTokenTypes.BLOCK_COMMENT -> ADMHighlighterColors.BLOCK_COMMENT
            ADMTokenTypes.OPERATOR -> ADMHighlighterColors.OPERATOR
            ADMTokenTypes.PAREN_OPEN, ADMTokenTypes.PAREN_CLOSE -> ADMHighlighterColors.PARENTHESES
            ADMTokenTypes.BRACE_OPEN, ADMTokenTypes.BRACE_CLOSE -> ADMHighlighterColors.BRACES
            ADMTokenTypes.BRACKET_OPEN, ADMTokenTypes.BRACKET_CLOSE -> ADMHighlighterColors.BRACKETS
            ADMTokenTypes.COMMA -> ADMHighlighterColors.COMMA
            ADMTokenTypes.DOT -> ADMHighlighterColors.DOT
            ADMTokenTypes.COLON -> ADMHighlighterColors.COLON
            ADMTokenTypes.SEMICOLON -> ADMHighlighterColors.SEMICOLON
            ADMTokenTypes.ATTRIBUTE -> ADMHighlighterColors.ATTRIBUTE
            ADMTokenTypes.BAD_CHARACTER -> ADMHighlighterColors.BAD_CHARACTER
            else -> null
        }
        return pack(key)
    }
}
