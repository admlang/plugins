package org.adm.intellij.highlighting

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
private fun fallbackKey(name: String, fallback: TextAttributesKey): TextAttributesKey =
    TextAttributesKey.createTextAttributesKey(name, fallback)

object ADMHighlighterColors {
    val KEYWORD: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_KEYWORD",
        DefaultLanguageHighlighterColors.KEYWORD
    )

    val BUILTIN_TYPE: TextAttributesKey = fallbackKey("ADM_BUILTIN_TYPE", DefaultLanguageHighlighterColors.KEYWORD)

    val IDENTIFIER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_IDENTIFIER",
        DefaultLanguageHighlighterColors.IDENTIFIER
    )

    val NUMBER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_NUMBER",
        DefaultLanguageHighlighterColors.NUMBER
    )

    val STRING: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_STRING",
        DefaultLanguageHighlighterColors.STRING
    )

    val CHAR: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_CHAR",
        DefaultLanguageHighlighterColors.STRING
    )

    val REGEX: TextAttributesKey = fallbackKey("ADM_REGEX", DefaultLanguageHighlighterColors.STRING)

    val LINE_COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_LINE_COMMENT",
        DefaultLanguageHighlighterColors.LINE_COMMENT
    )

    val BLOCK_COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_BLOCK_COMMENT",
        DefaultLanguageHighlighterColors.BLOCK_COMMENT
    )

    val OPERATOR: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_OPERATOR",
        DefaultLanguageHighlighterColors.OPERATION_SIGN
    )

    val DOT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_DOT",
        DefaultLanguageHighlighterColors.DOT
    )

    val COMMA: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_COMMA",
        DefaultLanguageHighlighterColors.COMMA
    )

    val COLON: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_COLON",
        DefaultLanguageHighlighterColors.OPERATION_SIGN
    )

    val SEMICOLON: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_SEMICOLON",
        DefaultLanguageHighlighterColors.SEMICOLON
    )

    val PARENTHESES: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_PARENTHESES",
        DefaultLanguageHighlighterColors.PARENTHESES
    )

    val BRACES: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_BRACES",
        DefaultLanguageHighlighterColors.BRACES
    )

    val BRACKETS: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_BRACKETS",
        DefaultLanguageHighlighterColors.BRACKETS
    )

    val ATTRIBUTE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_ATTRIBUTE",
        DefaultLanguageHighlighterColors.METADATA
    )

    val BAD_CHARACTER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_BAD_CHARACTER",
        DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE
    )

    val ROOT_DECLARATION: TextAttributesKey = fallbackKey("ADM_ROOT_DECLARATION", KEYWORD)
    val MODIFIER_PRIMARY: TextAttributesKey = fallbackKey("ADM_MODIFIER_PRIMARY", KEYWORD)
    val MODIFIER_FOREIGN: TextAttributesKey = fallbackKey("ADM_MODIFIER_FOREIGN", KEYWORD)
    val TRANSACTION_KEYWORD: TextAttributesKey = fallbackKey("ADM_TRANSACTION_KEYWORD", DefaultLanguageHighlighterColors.STATIC_METHOD)
    val GUARD_KEYWORD: TextAttributesKey = fallbackKey("ADM_GUARD_KEYWORD", DefaultLanguageHighlighterColors.INSTANCE_METHOD)
    val CHECK_KEYWORD: TextAttributesKey = fallbackKey("ADM_CHECK_KEYWORD", DefaultLanguageHighlighterColors.STATIC_FIELD)
    val ONERROR_KEYWORD: TextAttributesKey = fallbackKey("ADM_ONERROR_KEYWORD", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val FAIL_KEYWORD: TextAttributesKey = fallbackKey("ADM_FAIL_KEYWORD", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL)
    val MODULE_IDENTIFIER: TextAttributesKey = fallbackKey("ADM_MODULE_IDENTIFIER", DefaultLanguageHighlighterColors.CLASS_NAME)
    val CONSTANT_IDENTIFIER: TextAttributesKey = fallbackKey("ADM_CONSTANT_IDENTIFIER", DefaultLanguageHighlighterColors.CONSTANT)
    val CHECK_BLOCK: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "ADM_CHECK_BLOCK",
        DefaultLanguageHighlighterColors.TEMPLATE_LANGUAGE_COLOR
    )
}
