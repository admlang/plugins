package org.adm.intellij.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import org.adm.intellij.ADMIcons
import javax.swing.Icon

class ADMColorSettingsPage : ColorSettingsPage {
    private val descriptors = arrayOf(
        AttributesDescriptor("Keyword", ADMHighlighterColors.KEYWORD),
        AttributesDescriptor("Built-in type", ADMHighlighterColors.BUILTIN_TYPE),
        AttributesDescriptor("Identifier", ADMHighlighterColors.IDENTIFIER),
        AttributesDescriptor("Number", ADMHighlighterColors.NUMBER),
        AttributesDescriptor("String", ADMHighlighterColors.STRING),
        AttributesDescriptor("Character", ADMHighlighterColors.CHAR),
        AttributesDescriptor("Regex", ADMHighlighterColors.REGEX),
        AttributesDescriptor("Line comment", ADMHighlighterColors.LINE_COMMENT),
        AttributesDescriptor("Block comment", ADMHighlighterColors.BLOCK_COMMENT),
        AttributesDescriptor("Operator", ADMHighlighterColors.OPERATOR),
        AttributesDescriptor("Parentheses", ADMHighlighterColors.PARENTHESES),
        AttributesDescriptor("Braces", ADMHighlighterColors.BRACES),
        AttributesDescriptor("Brackets", ADMHighlighterColors.BRACKETS),
        AttributesDescriptor("Comma", ADMHighlighterColors.COMMA),
        AttributesDescriptor("Dot", ADMHighlighterColors.DOT),
        AttributesDescriptor("Colon", ADMHighlighterColors.COLON),
        AttributesDescriptor("Semicolon", ADMHighlighterColors.SEMICOLON),
        AttributesDescriptor("Attribute", ADMHighlighterColors.ATTRIBUTE),
        AttributesDescriptor("Root declaration keyword", ADMHighlighterColors.ROOT_DECLARATION),
        AttributesDescriptor("Primary modifier keyword", ADMHighlighterColors.MODIFIER_PRIMARY),
        AttributesDescriptor("Foreign modifier keyword", ADMHighlighterColors.MODIFIER_FOREIGN),
        AttributesDescriptor("Transaction keyword", ADMHighlighterColors.TRANSACTION_KEYWORD),
        AttributesDescriptor("Guard keyword", ADMHighlighterColors.GUARD_KEYWORD),
        AttributesDescriptor("Check keyword", ADMHighlighterColors.CHECK_KEYWORD),
        AttributesDescriptor("Onerror keyword", ADMHighlighterColors.ONERROR_KEYWORD),
        AttributesDescriptor("Fail keyword", ADMHighlighterColors.FAIL_KEYWORD),
        AttributesDescriptor("Module identifier", ADMHighlighterColors.MODULE_IDENTIFIER),
        AttributesDescriptor("Constant identifier", ADMHighlighterColors.CONSTANT_IDENTIFIER),
        AttributesDescriptor("Bad character", ADMHighlighterColors.BAD_CHARACTER),
        AttributesDescriptor("Check block background", ADMHighlighterColors.CHECK_BLOCK)
    )

    override fun getDisplayName(): String = "ADM"

    override fun getIcon(): Icon? = ADMIcons.FILE

    override fun getHighlighter() = ADMSyntaxHighlighter()

    override fun getDemoText(): String = """
        application SampleApp {
            use std.net.http
            use (
                std.async,
                utils.math::(lerp as interpolate)
            )

            const epsilon float32 = 0.000_1
            let timeout duration = 1s 250ms
            let pattern regex = /[a-z]{1,}+/mg
            let banner string = ${"\"\"\""}
                Multi-line
                string literal
            ${"\"\"\""}
            let letter char = 'Z'

            // single line comment
            /* nested block comment
               /* spanning multiple levels */
            */

            transaction {
                begin {
                    defer cleanup()
                }

                if timeout >= 1s ?? (true, false) {
                    fail "too short"
                }
            } onerror (err error) {
                recover none
            }
        }

        component LoginForm {
            view {
                VStack(spacing=12) {
                    Text("Welcome {user.name}") when user.isLoggedIn
                    Button("Login", onClick=submit()) {
                        Icon("user")
                    }
                }
            }

            style {
                centered = {
                    padding: 16,
                    backgroundColor: palette.surface,
                }

                on = {
                    hover: { opacity: 0.9 },
                }
            }

            type {
                let state = {
                    loading: false
                }

                @on(UI, "click", "submit")
                async def submit() !bool {
                    expects {
                        assert !state.loading
                    }

                    provides {
                        assert state.loading
                    }
                }
            }
        }
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
}
