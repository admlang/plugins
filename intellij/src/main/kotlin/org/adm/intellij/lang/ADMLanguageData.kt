package org.adm.intellij.lang

object ADMLanguageData {
    val KEYWORDS = setOf(
        "application", "library", "plugin", "module",
        "view", "style", "component", "service", "type", "struct", "datatype", "enum", "union", "interface",
        "partial", "internal",
        "let", "const", "def", "use", "self",
        "atomic", "weak", "async", "cuda", "sql", "infix", "meta",
        "return", "fail", "expects", "provides", "begin", "rollback", "transaction", "defer", "yield", "try",
        "onerror", "await",
        "if", "else", "when", "unless",
        "as", "is", "in",
        "for", "forall", "every", "empty", "break", "finish",
        "switch", "match", "select", "where", "case", "continue", "default",
        "assert", "recover", "new", "dispose"
    )

    val BOOLEAN_LITERALS = setOf("true", "false")
    const val NONE_LITERAL = "none"

    val BUILTIN_TYPES = setOf(
        "bool", "none",
        "int", "int8", "int16", "int32", "int64", "int128",
        "uint", "uint8", "uint16", "uint32", "uint64", "uint128",
        "bigint",
        "float", "float16", "float32", "float64", "float128",
        "complex32", "complex64", "complex128",
        "byte", "char", "string", "regex", "duration",
        "map", "tuple", "channel", "future",
    )

    val ROOT_DECLARATIONS = setOf("application", "plugin", "library", "module")
    val PRIMARY_MODIFIERS = setOf("async", "atomic", "weak", "internal", "partial")
    val FOREIGN_MODIFIERS = setOf("cuda", "sql", "infix")
    val TRANSACTION_KEYWORDS = setOf("transaction", "begin", "rollback")
    val GUARD_KEYWORDS = setOf("expects", "provides")
    const val CHECK_KEYWORD = "check"
    const val ONERROR_KEYWORD = "onerror"
    const val FAIL_KEYWORD = "fail"

    val MULTI_CHAR_OPERATORS = listOf(
        "<<<=",
        ">>>=",
        "??",
        ".*",
        "<<<",
        ">>>",
        "<<=",
        ">>=",
        "<<",
        ">>",
        "==",
        "!=",
        "<=",
        ">=",
        "&&",
        "||",
        "++",
        "--",
        "**",
        "+=",
        "-=",
        "*=",
        "/=",
        "%=",
        "&=",
        "|=",
        "^=",
        ":=",
        "...",
        "..",
        "::",
        "<-",
        "->"
    )

    val SINGLE_OPERATORS = setOf('=', '+', '-', '*', '/', '%', '&', '|', '^', '!', '<', '>', '?', '~')

    val REGEX_ALLOWED_PREVIOUS_CHARS = setOf('=', '(', '[', '{', ',', ':', ';', '?', '!', '+', '-', '*', '%', '&', '|', '^', '~', '<', '>')
    val REGEX_ALLOWED_PREVIOUS_KEYWORDS = setOf(
        "return", "case", "default", "match", "when", "unless", "if", "else",
        "await", "try", "recover", "fail", "onerror", "assert", "provides", "expects", "begin"
    )
}
