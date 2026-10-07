package org.adm.intellij.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class ADMSettingsConfigurable : Configurable {
    private var component: JComponent? = null
    private var admExeField: TextFieldWithBrowseButton? = null
    private var admHomeField: TextFieldWithBrowseButton? = null
    private var admCacheField: TextFieldWithBrowseButton? = null
    private var admLibField: TextFieldWithBrowseButton? = null
    private var lldbExeField: TextFieldWithBrowseButton? = null
    private var backendCombo: ComboBox<ADMBackend>? = null
    private var logProtocolBox: javax.swing.JCheckBox? = null
    private var renderDocsBox: javax.swing.JCheckBox? = null
    private var lintEditorBox: javax.swing.JCheckBox? = null

    override fun getDisplayName(): String = "ADM"

    override fun createComponent(): JComponent {
        val exe = TextFieldWithBrowseButton()
        exe.addBrowseFolderListener(
            "Select adm executable",
            "Select the `adm` executable to use for running and LSP.",
            null,
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(),
        )

        val home = TextFieldWithBrowseButton()
        home.addBrowseFolderListener(
            "Select ADM home directory (ADM_HOME)",
            "Select the ADM installation directory (e.g. ~/.adm). Sets ADM_HOME for ADM commands.",
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor(),
        )

        val cache = TextFieldWithBrowseButton()
        cache.addBrowseFolderListener(
            "Select ADM cache directory (ADM_CACHE)",
            "Select the ADM cache directory (recommended: ~/.adm/cache). Sets ADM_CACHE for ADM commands.",
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor(),
        )

        val lib = TextFieldWithBrowseButton()
        lib.addBrowseFolderListener(
            "Select ADM library directory (ADM_LIB)",
            "Select the directory containing ADM's prelude/ and std/ folders. Sets ADM_LIB for ADM commands.",
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor(),
        )

        val lldb = TextFieldWithBrowseButton()
        lldb.addBrowseFolderListener(
            "Select LLDB executable (ADM_LLDB)",
            "Select the `lldb` executable to use for debugging. Sets ADM_LLDB for the debugger bridge.",
            null,
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(),
        )

        val backend = ComboBox(ADMBackend.entries.toTypedArray())
        backendCombo = backend

        val logProtocol = javax.swing.JCheckBox("Log LSP protocol traffic to the IDE log")
        logProtocolBox = logProtocol
        val renderDocs = javax.swing.JCheckBox("Render documentation comments by default")
        renderDocsBox = renderDocs
        val lintEditor = javax.swing.JCheckBox("Show lint findings in the editor")
        lintEditorBox = lintEditor

        admExeField = exe
        admHomeField = home
        admCacheField = cache
        admLibField = lib
        lldbExeField = lldb

        val state = ADMSettingsState.getInstance()
        exe.text = state.admExecutablePath
        home.text = state.admHomePath
        cache.text = state.admCachePath
        lib.text = state.admLibPath
        lldb.text = state.lldbExecutablePath
        backend.selectedItem = ADMBackend.fromId(state.backend)
        logProtocol.isSelected = state.logLspProtocol
        renderDocs.isSelected = state.renderDocComments
        lintEditor.isSelected = state.lintInEditor

        component = panel {
            row("ADM executable path:") { cell(exe).align(AlignX.FILL) }
            row("ADM home directory (ADM_HOME):") { cell(home).align(AlignX.FILL) }
            row("ADM cache directory (ADM_CACHE):") { cell(cache).align(AlignX.FILL) }
            row("ADM library directory (ADM_LIB):") { cell(lib).align(AlignX.FILL) }
            row("LLDB executable (ADM_LLDB):") { cell(lldb).align(AlignX.FILL) }
            row("Compiler backend:") { cell(backend) }
                .comment("Passed to `adm` as <code>--backend</code> when building, running, testing and debugging.")
            row { cell(logProtocol) }
                .comment("Runs <code>adm lsp --log-protocol</code>. Verbose; restart the IDE or reopen a file to apply.")
            row { cell(renderDocs) }
                .comment("Opens ADM files with <code>//</code> doc comments shown formatted; the gutter pencil toggles one comment, Ctrl+Alt+Q the whole file. Applies to files opened from now on.")
            row { cell(lintEditor) }
                .comment("The language server runs <code>adm lint</code> on the open files: findings show as warnings and weak warnings with Alt+Enter fixes. Checks switched off on the ADM tool window's Lint tab are skipped. Restart the language server to apply.")
        }
        return component!!
    }

    override fun isModified(): Boolean {
        val state = ADMSettingsState.getInstance()
        return (admExeField?.text ?: "") != state.admExecutablePath ||
            (admHomeField?.text ?: "") != state.admHomePath ||
            (admCacheField?.text ?: "") != state.admCachePath ||
            (admLibField?.text ?: "") != state.admLibPath ||
            (lldbExeField?.text ?: "") != state.lldbExecutablePath ||
            selectedBackend().id != ADMBackend.fromId(state.backend).id ||
            (logProtocolBox?.isSelected ?: false) != state.logLspProtocol ||
            (renderDocsBox?.isSelected ?: true) != state.renderDocComments ||
            (lintEditorBox?.isSelected ?: true) != state.lintInEditor
    }

    private fun selectedBackend(): ADMBackend =
        (backendCombo?.selectedItem as? ADMBackend) ?: ADMBackend.DEFAULT

    override fun apply() {
        val state = ADMSettingsState.getInstance()
        state.admExecutablePath = admExeField?.text ?: ""
        state.admHomePath = admHomeField?.text ?: ""
        state.admCachePath = admCacheField?.text ?: ""
        state.admLibPath = admLibField?.text ?: ""
        state.lldbExecutablePath = lldbExeField?.text ?: ""
        state.backend = selectedBackend().id
        state.logLspProtocol = logProtocolBox?.isSelected ?: false
        state.renderDocComments = renderDocsBox?.isSelected ?: true
        state.lintInEditor = lintEditorBox?.isSelected ?: true
    }

    override fun reset() {
        val state = ADMSettingsState.getInstance()
        admExeField?.text = state.admExecutablePath
        admHomeField?.text = state.admHomePath
        admCacheField?.text = state.admCachePath
        admLibField?.text = state.admLibPath
        lldbExeField?.text = state.lldbExecutablePath
        backendCombo?.selectedItem = ADMBackend.fromId(state.backend)
        logProtocolBox?.isSelected = state.logLspProtocol
        renderDocsBox?.isSelected = state.renderDocComments
        lintEditorBox?.isSelected = state.lintInEditor
    }

    override fun disposeUIResources() {
        component = null
        admExeField = null
        admHomeField = null
        admCacheField = null
        admLibField = null
        lldbExeField = null
        backendCombo = null
        logProtocolBox = null
    }
}
