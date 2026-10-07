package org.adm.intellij.lang

import com.intellij.psi.tree.IElementType
import com.intellij.psi.TokenType

class ADMTokenType(debugName: String) : IElementType(debugName, ADMLanguage)

object ADMTokenTypes {
    // Basic tokens
    val WHITE_SPACE = ADMTokenType("WHITE_SPACE")
    val LINE_COMMENT = ADMTokenType("LINE_COMMENT")
    val BLOCK_COMMENT = ADMTokenType("BLOCK_COMMENT")

    // Literals
    val IDENTIFIER = ADMTokenType("IDENTIFIER")
    val STRING = ADMTokenType("STRING")
    val CHAR = ADMTokenType("CHAR")
    val NUMBER = ADMTokenType("NUMBER")
    val REGEX = ADMTokenType("REGEX")
    val BUILTIN_TYPE = ADMTokenType("BUILTIN_TYPE")

    // Keywords - now separated by category
    val KEYWORD = ADMTokenType("KEYWORD")
    val ROOT_DECLARATION = ADMTokenType("ROOT_DECLARATION")
    val MODIFIER_PRIMARY = ADMTokenType("MODIFIER_PRIMARY")
    val MODIFIER_FOREIGN = ADMTokenType("MODIFIER_FOREIGN")
    val TRANSACTION_KEYWORD = ADMTokenType("TRANSACTION_KEYWORD")
    val GUARD_KEYWORD = ADMTokenType("GUARD_KEYWORD")
    val CHECK_KEYWORD = ADMTokenType("CHECK_KEYWORD")
    val ONERROR_KEYWORD = ADMTokenType("ONERROR_KEYWORD")
    val FAIL_KEYWORD = ADMTokenType("FAIL_KEYWORD")

    // Punctuation
    val PAREN_OPEN = ADMTokenType("PAREN_OPEN")
    val PAREN_CLOSE = ADMTokenType("PAREN_CLOSE")
    val BRACE_OPEN = ADMTokenType("BRACE_OPEN")
    val BRACE_CLOSE = ADMTokenType("BRACE_CLOSE")
    val BRACKET_OPEN = ADMTokenType("BRACKET_OPEN")
    val BRACKET_CLOSE = ADMTokenType("BRACKET_CLOSE")
    val COMMA = ADMTokenType("COMMA")
    val SEMICOLON = ADMTokenType("SEMICOLON")
    val COLON = ADMTokenType("COLON")
    val DOT = ADMTokenType("DOT")
    val OPERATOR = ADMTokenType("OPERATOR")
    val ATTRIBUTE = ADMTokenType("ATTRIBUTE")
    val BAD_CHARACTER = ADMTokenType("BAD_CHARACTER")
}
