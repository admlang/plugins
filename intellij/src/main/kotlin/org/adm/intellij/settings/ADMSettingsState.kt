package org.adm.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "ADMSettings",
    storages = [Storage("adm.xml")],
)
class ADMSettingsState : PersistentStateComponent<ADMSettingsState> {
    var admExecutablePath: String = ""
    var admHomePath: String = ""
    var admCachePath: String = ""
    var admLibPath: String = ""
    var lldbExecutablePath: String = ""

    // Compiler backend passed to `adm` as `--backend`. Mirrors the compiler's
    // own default, so an unset value behaves exactly like invoking `adm`
    // without the flag.
    var backend: String = ADMBackend.DEFAULT.id

    // Runs `adm lsp --log-protocol`, so every LSP request and response is
    // written to the IDE log. Off by default: it is verbose, and only wanted
    // when diagnosing why a language feature returns nothing.
    var logLspProtocol: Boolean = false

    // Asks the language server to lint the open files as it type-checks
    // them, so lint findings show in the editor and the Problems view with
    // Alt+Enter fixes. The checks switched off on the Lint tab are skipped.
    var lintInEditor: Boolean = true

    // Environment variables (ADM_* toggles) handed to every adm process the
    // plugin starts: the language server, builds, runs and tests. Edited on
    // the tool window's Toolchain tab.
    var extraEnv: MutableMap<String, String> = LinkedHashMap()

    override fun getState(): ADMSettingsState = this

    override fun loadState(state: ADMSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): ADMSettingsState =
            ApplicationManager.getApplication().getService(ADMSettingsState::class.java)
    }
}
