package org.adm.intellij.run

import org.adm.intellij.lsp.ADMLspLocator
import org.adm.intellij.settings.ADMBackend
import org.adm.intellij.settings.ADMSettingsState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object ADMExec {
    fun resolve(projectBasePath: String?): Path? {
        val settings = ADMSettingsState.getInstance()
        val configured = settings.admExecutablePath.trim()
        if (configured.isNotEmpty()) {
            val p = Paths.get(configured)
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return p
        }

        val workDir = projectBasePath?.let { runCatching { Path.of(it) }.getOrNull() }
        return ADMLspLocator.findADMExecutable(workDir)
    }

    fun defaultCacheDir(): String {
        val settings = ADMSettingsState.getInstance()
        val configured = settings.admCachePath.trim()
        if (configured.isNotEmpty()) return configured
        val home = System.getProperty("user.home") ?: ""
        if (home.isNotBlank()) return Paths.get(home).resolve(".adm").resolve("cache").toString()
        return ""
    }

    fun backend(): ADMBackend = ADMBackend.fromId(ADMSettingsState.getInstance().backend)

    /**
     * Returns [args] with the configured `--backend` appended when the
     * subcommand accepts it. Only `build`, `run` and `test` take the flag --
     * adding it to `lsp` or `fmt` would make `adm` reject the whole command --
     * and an explicit `--backend` already in [args] always wins.
     */
    fun withBackend(args: List<String>): List<String> {
        val subcommand = args.firstOrNull()?.trim()?.lowercase()
        if (subcommand !in BACKEND_SUBCOMMANDS) return args
        if (args.any { it == "--backend" || it.startsWith("--backend=") }) return args
        return args + listOf("--backend", backend().id)
    }

    private val BACKEND_SUBCOMMANDS = setOf("build", "run", "test")

    fun env(): Map<String, String> {
        val settings = ADMSettingsState.getInstance()
        val env = LinkedHashMap<String, String>()
        val home = settings.admHomePath.trim()
        val cache = defaultCacheDir().trim()
        val lib = settings.admLibPath.trim()
        val lldb = settings.lldbExecutablePath.trim()
        if (home.isNotEmpty()) env["ADM_HOME"] = home
        if (cache.isNotEmpty()) env["ADM_CACHE"] = cache
        if (lib.isNotEmpty()) env["ADM_LIB"] = lib
        if (lldb.isNotEmpty()) env["ADM_LLDB"] = lldb
        for ((k, v) in settings.extraEnv) {
            if (k.isNotBlank() && v.isNotBlank()) env[k] = v
        }
        return env
    }
}
