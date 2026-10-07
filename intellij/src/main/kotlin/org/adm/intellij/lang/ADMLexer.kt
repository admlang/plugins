package org.adm.intellij.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

class ADMLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var endOffset: Int = 0
    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        tokenType = null
        tokenStart = startOffset
        tokenEnd = startOffset
        locateToken(startOffset)
    }

    override fun getState(): Int = 0

    override fun getTokenType(): IElementType? = tokenType

    override fun getTokenStart(): Int = tokenStart

    override fun getTokenEnd(): Int = tokenEnd

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        if (tokenType == null) {
            return
        }
        locateToken(tokenEnd)
    }

    private fun locateToken(offset: Int) {
        val index = offset

        if (index >= endOffset) {
            tokenType = null
            tokenStart = endOffset
            tokenEnd = endOffset
            return
        }

        tokenStart = index
        val ch = buffer[index]

        if (ch.isWhitespace()) {
            lexWhitespace(index)
            return
        }

        when {
            ch == '/' && match(index + 1, '/') -> {
                lexLineComment(index)
                return
            }
            ch == '/' && match(index + 1, '*') -> {
                lexBlockComment(index)
                return
            }
            ch == '/' && lexRegexLiteral(index) -> {
                return
            }
            ch == '"' -> {
                lexString(index)
                return
            }
            ch == '\'' -> {
                lexChar(index)
                return
            }
            ch == '@' -> {
                lexAttribute(index)
                return
            }
            ch == '.' -> {
                val next = if (index + 1 < endOffset) buffer[index + 1] else '\u0000'
                if (next.isDigit()) {
                    lexNumber(index)
                } else {
                    lexDotOrRange(index)
                }
                return
            }
            ch == '(' -> {
                setToken(ADMTokenTypes.PAREN_OPEN, index + 1)
                return
            }
            ch == ')' -> {
                setToken(ADMTokenTypes.PAREN_CLOSE, index + 1)
                return
            }
            ch == '{' -> {
                setToken(ADMTokenTypes.BRACE_OPEN, index + 1)
                return
            }
            ch == '}' -> {
                setToken(ADMTokenTypes.BRACE_CLOSE, index + 1)
                return
            }
            ch == '[' -> {
                setToken(ADMTokenTypes.BRACKET_OPEN, index + 1)
                return
            }
            ch == ']' -> {
                setToken(ADMTokenTypes.BRACKET_CLOSE, index + 1)
                return
            }
            ch == ',' -> {
                setToken(ADMTokenTypes.COMMA, index + 1)
                return
            }
            ch == ';' -> {
                setToken(ADMTokenTypes.SEMICOLON, index + 1)
                return
            }
            ch == ':' -> {
                if (match(index + 1, ':')) {
                    setToken(ADMTokenTypes.OPERATOR, index + 2)
                } else {
                    setToken(ADMTokenTypes.COLON, index + 1)
                }
                return
            }
        }

        if (ch.isDigit()) {
            lexNumber(index)
            return
        }

        if (ch.isIdentifierStart()) {
            lexIdentifier(index)
            return
        }

        if (lexOperator(index)) {
            return
        }

        setToken(ADMTokenTypes.BAD_CHARACTER, index + 1)
    }

    private fun lexWhitespace(start: Int) {
        var index = start + 1
        while (index < endOffset && buffer[index].isWhitespace()) {
            index++
        }
        setToken(ADMTokenTypes.WHITE_SPACE, index)
    }

    private fun lexLineComment(start: Int) {
        var index = start + 2
        while (index < endOffset && buffer[index] != '\n') {
            index++
        }
        setToken(ADMTokenTypes.LINE_COMMENT, index)
    }

    private fun lexBlockComment(start: Int) {
        var index = start + 2
        var depth = 1
        while (index < endOffset) {
            if (match(index, '/') && match(index + 1, '*')) {
                depth++
                index += 2
                continue
            }
            if (match(index, '*') && match(index + 1, '/')) {
                depth--
                index += 2
                if (depth == 0) {
                    break
                }
                continue
            }
            index++
        }
        if (depth == 0) {
            setToken(ADMTokenTypes.BLOCK_COMMENT, index)
        } else {
            setToken(ADMTokenTypes.BLOCK_COMMENT, endOffset)
        }
    }

    private fun lexRegexLiteral(start: Int): Boolean {
        if (!isRegexLiteralContext(start)) {
            return false
        }
        val end = findRegexLiteralEnd(start) ?: return false
        setToken(ADMTokenTypes.REGEX, end)
        return true
    }

    private fun lexString(start: Int) {
        val isMultiline = match(start + 1, '"') && match(start + 2, '"')
        var index = if (isMultiline) start + 3 else start + 1
        var escaped = false
        while (index < endOffset) {
            val ch = buffer[index]
            if (isMultiline) {
                if (ch == '"' && match(index + 1, '"') && match(index + 2, '"')) {
                    index += 3
                    break
                }
                index++
                continue
            }
            if (!escaped && ch == '"') {
                index++
                break
            }
            if (!escaped && ch == '\n') {
                break
            }
            if (!escaped && ch == '\\') {
                escaped = true
                index++
                continue
            }
            escaped = false
            index++
        }
        setToken(ADMTokenTypes.STRING, index)
    }

    private fun lexChar(start: Int) {
        var index = start + 1
        var escaped = false
        while (index < endOffset) {
            val ch = buffer[index]
            if (!escaped && ch == '\'') {
                index++
                break
            }
            if (!escaped && ch == '\n') {
                break
            }
            escaped = !escaped && ch == '\\'
            if (escaped) {
                index++
                continue
            }
            index++
        }
        setToken(ADMTokenTypes.CHAR, index)
    }

    private fun lexAttribute(start: Int) {
        var index = start + 1
        while (index < endOffset && buffer[index].isWhitespace()) {
            index++
        }
        while (index < endOffset && buffer[index].isIdentifierPartOrDot()) {
            index++
        }
        setToken(ADMTokenTypes.ATTRIBUTE, index)
    }

    private fun lexDotOrRange(start: Int) {
        when {
            match(start + 1, '.') && match(start + 2, '.') -> setToken(ADMTokenTypes.OPERATOR, start + 3)
            match(start + 1, '.') -> setToken(ADMTokenTypes.OPERATOR, start + 2)
            else -> setToken(ADMTokenTypes.DOT, start + 1)
        }
    }

    private fun lexNumber(start: Int) {
        var index = start
        var hasDot = false
        var hasExp = false
        var seenUnitSuffix = false

        if (match(index, '0') && index + 1 < endOffset) {
            val next = buffer[index + 1]
            if (next == 'x' || next == 'X') {
                index += 2
                while (index < endOffset && buffer[index].isHexDigitOrSeparator()) {
                    index++
                }
                setToken(ADMTokenTypes.NUMBER, index)
                return
            }
            if (next == 'b' || next == 'B') {
                index += 2
                while (index < endOffset && buffer[index].isBinaryDigitOrSeparator()) {
                    index++
                }
                setToken(ADMTokenTypes.NUMBER, index)
                return
            }
            if (next == 'o' || next == 'O') {
                index += 2
                while (index < endOffset && buffer[index].isOctalDigitOrSeparator()) {
                    index++
                }
                setToken(ADMTokenTypes.NUMBER, index)
                return
            }
        }

        while (index < endOffset) {
            val ch = buffer[index]
            when {
                ch.isDigit() -> {
                    index++
                    continue
                }
                ch == '_' -> {
                    index++
                    continue
                }
                ch == ' ' -> {
                    val next = nextNonSpace(index + 1)
                    if (next != null && (next.isDigit() || next.isLetter())) {
                        index++
                        continue
                    }
                    break
                }
                ch == '.' && !hasDot -> {
                    val next = if (index + 1 < endOffset) buffer[index + 1] else '\u0000'
                    if (!next.isDigit()) {
                        break
                    }
                    hasDot = true
                    index++
                    continue
                }
                (ch == 'e' || ch == 'E' || ch == 'p' || ch == 'P') && !hasExp -> {
                    hasExp = true
                    index++
                    if (index < endOffset && (buffer[index] == '+' || buffer[index] == '-')) {
                        index++
                    }
                    continue
                }
                ch.isLetter() -> {
                    seenUnitSuffix = true
                    index++
                    continue
                }
                seenUnitSuffix && ch.isDigit() -> {
                    index++
                    continue
                }
                else -> break
            }
        }

        // imaginary numbers end with single 'i'
        if (match(index, 'i')) {
            index++
        }

        setToken(ADMTokenTypes.NUMBER, index)
    }

    private fun lexIdentifier(start: Int) {
        var index = start + 1
        while (index < endOffset && buffer[index].isIdentifierPart()) {
            index++
        }
        val literal = buffer.subSequence(start, index).toString()
        tokenType = when {
            // Check specific keyword categories first (most specific to least specific)
            literal in ADMLanguageData.ROOT_DECLARATIONS -> ADMTokenTypes.ROOT_DECLARATION
            literal in ADMLanguageData.PRIMARY_MODIFIERS -> ADMTokenTypes.MODIFIER_PRIMARY
            literal in ADMLanguageData.FOREIGN_MODIFIERS -> ADMTokenTypes.MODIFIER_FOREIGN
            literal in ADMLanguageData.TRANSACTION_KEYWORDS -> ADMTokenTypes.TRANSACTION_KEYWORD
            literal in ADMLanguageData.GUARD_KEYWORDS -> ADMTokenTypes.GUARD_KEYWORD
            literal == ADMLanguageData.CHECK_KEYWORD -> ADMTokenTypes.CHECK_KEYWORD
            literal == ADMLanguageData.ONERROR_KEYWORD -> ADMTokenTypes.ONERROR_KEYWORD
            literal == ADMLanguageData.FAIL_KEYWORD -> ADMTokenTypes.FAIL_KEYWORD
            // Then check general categories
            literal in ADMLanguageData.KEYWORDS -> ADMTokenTypes.KEYWORD
            literal in ADMLanguageData.BOOLEAN_LITERALS -> ADMTokenTypes.KEYWORD
            literal == ADMLanguageData.NONE_LITERAL -> ADMTokenTypes.KEYWORD
            literal in ADMLanguageData.BUILTIN_TYPES -> ADMTokenTypes.BUILTIN_TYPE
            else -> ADMTokenTypes.IDENTIFIER
        }
        tokenStart = start
        tokenEnd = index
    }

    private fun findRegexLiteralEnd(start: Int): Int? {
        var index = start + 1
        var escaped = false
        var inCharClass = false

        if (index >= endOffset) {
            return null
        }

        val first = buffer[index]
        if (first.isWhitespace() || first == '=') {
            return null
        }

        while (index < endOffset) {
            val ch = buffer[index]
            if (!escaped) {
                when (ch) {
                    '\n', '\r' -> return null
                    '\\' -> escaped = true
                    '[' -> inCharClass = true
                    ']' -> inCharClass = false
                    '/' -> if (!inCharClass) {
                        var flagsIndex = index + 1
                        while (flagsIndex < endOffset && buffer[flagsIndex].isLetter()) {
                            flagsIndex++
                        }
                        return flagsIndex
                    }
                }
            } else {
                escaped = false
            }
            index++
        }
        return null
    }

    private fun isRegexLiteralContext(start: Int): Boolean {
        val previousIndex = previousNonWhitespaceIndex(start - 1) ?: return true
        val previousChar = buffer[previousIndex]

        if (previousChar in ADMLanguageData.REGEX_ALLOWED_PREVIOUS_CHARS) {
            return true
        }

        if (previousChar.isIdentifierPart()) {
            val identifier = extractIdentifierEndingAt(previousIndex)
            if (identifier in ADMLanguageData.REGEX_ALLOWED_PREVIOUS_KEYWORDS) {
                return true
            }
        }

        return false
    }

    private fun previousNonWhitespaceIndex(start: Int): Int? {
        var index = start
        while (index >= 0) {
            val ch = buffer[index]
            if (!ch.isWhitespace()) {
                return index
            }
            index--
        }
        return null
    }

    private fun extractIdentifierEndingAt(index: Int): String {
        var startIndex = index
        while (startIndex > 0 && buffer[startIndex - 1].isIdentifierPart()) {
            startIndex--
        }
        return buffer.subSequence(startIndex, index + 1).toString()
    }

    private fun lexOperator(start: Int): Boolean {
        for (op in ADMLanguageData.MULTI_CHAR_OPERATORS) {
            if (matches(op, start)) {
                setToken(ADMTokenTypes.OPERATOR, start + op.length)
                return true
            }
        }
        val current = buffer[start]
        if (current in ADMLanguageData.SINGLE_OPERATORS) {
            setToken(ADMTokenTypes.OPERATOR, start + 1)
            return true
        }
        return false
    }

    private fun setToken(type: IElementType, end: Int) {
        tokenType = type
        tokenEnd = minOf(end, endOffset)
    }

    private fun match(index: Int, expected: Char): Boolean = index < endOffset && buffer[index] == expected

    private fun matches(literal: String, start: Int): Boolean {
        if (start + literal.length > endOffset) {
            return false
        }
        for (i in literal.indices) {
            if (buffer[start + i] != literal[i]) {
                return false
            }
        }
        return true
    }

    private fun Char.isIdentifierStart(): Boolean = this == '_' || this.isLetter()

    private fun Char.isIdentifierPart(): Boolean = isIdentifierStart() || this.isDigit()

    private fun Char.isIdentifierPartOrDot(): Boolean = isIdentifierPart() || this == '.'

    private fun Char.isHexDigitOrSeparator(): Boolean = this == '_' || this.isDigit() || this in 'a'..'f' || this in 'A'..'F'

    private fun Char.isBinaryDigitOrSeparator(): Boolean = this == '_' || this == '0' || this == '1'

    private fun Char.isOctalDigitOrSeparator(): Boolean = this == '_' || (this in '0'..'7')

    private fun nextNonSpace(start: Int): Char? {
        var index = start
        while (index < endOffset) {
            val ch = buffer[index]
            if (ch != ' ') {
                return ch
            }
            index++
        }
        return null
    }
}
