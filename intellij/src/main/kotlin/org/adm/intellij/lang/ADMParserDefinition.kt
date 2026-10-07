package org.adm.intellij.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.extapi.psi.PsiFileBase

class ADMParserDefinition : ParserDefinition {
    companion object {
        private val FILE = IFileElementType(ADMLanguage)

        private val WHITE_SPACES = TokenSet.create(ADMTokenTypes.WHITE_SPACE, TokenType.WHITE_SPACE)
        private val COMMENTS = TokenSet.create(ADMTokenTypes.LINE_COMMENT, ADMTokenTypes.BLOCK_COMMENT)
        private val STRING_LITERALS = TokenSet.create(ADMTokenTypes.STRING, ADMTokenTypes.CHAR, ADMTokenTypes.REGEX)
    }

    override fun createLexer(project: Project?) = ADMLexer()

    override fun createParser(project: Project?): PsiParser = ADMPsiParser()

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getWhitespaceTokens(): TokenSet = WHITE_SPACES

    override fun getCommentTokens(): TokenSet = COMMENTS

    override fun getStringLiteralElements(): TokenSet = STRING_LITERALS

    override fun createElement(node: ASTNode): PsiElement = node.psi

    override fun createFile(viewProvider: FileViewProvider): PsiFile = ADMFile(viewProvider)

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode, right: ASTNode): ParserDefinition.SpaceRequirements =
        ParserDefinition.SpaceRequirements.MAY
}

private class ADMPsiParser : PsiParser {
    override fun parse(root: com.intellij.psi.tree.IElementType, builder: PsiBuilder): ASTNode {
        val mark = builder.mark()
        while (!builder.eof()) {
            builder.advanceLexer()
        }
        mark.done(root)
        return builder.treeBuilt
    }
}

private class ADMFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ADMLanguage) {
    override fun getFileType() = ADMFileType.INSTANCE
    override fun toString(): String = "ADM File"
}

