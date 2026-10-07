package org.adm.intellij.lsp

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerListener
import org.eclipse.lsp4j.InitializeResult
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.platform.lsp.api.customization.LspFindReferencesSupport
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.services.LanguageServer
import org.adm.intellij.lang.ADMFileType
import org.adm.intellij.run.ADMExec
import org.adm.intellij.settings.ADMSettingsState
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.time.Instant

private val LOG = logger<ADMLspServerDescriptor>()

class ADMLspServerDescriptor(project: Project) : LspServerDescriptor(project, "ADM LSP") {
    override fun isSupportedFile(file: VirtualFile): Boolean = file.fileType == ADMFileType.INSTANCE

    /**
     * The platform's own LSP find-usages stays OFF. With it on, the platform
     * Find Usages action was enabled in ADM files and won the keymap's
     * shortcut over Show ADM Usages -- and its search came back "Nothing
     * found in 'Project Files'" because it has no PSI to anchor on. Usages
     * go through ADMSearchTarget instead (see org.adm.intellij.search).
     */
    override val lspFindReferencesSupport: LspFindReferencesSupport? = null

    /** LSP plus the ADM-specific requests (references with access kind, implements). */
    override val lsp4jServerClass: Class<out LanguageServer> = ADMLanguageServer::class.java

    /** Go to definition and type definition, both served by the ADM LSP. */
    override val lspGoToDefinitionSupport: Boolean = true
    override val lspGoToTypeDefinitionSupport: Boolean = true

    /** Hover cards: signatures, doc comments, type shapes. */
    override val lspHoverSupport: Boolean = true

    /**
     * How a diagnostic reads in the editor. A lint finding arrives as the
     * message, a newline, and the fix hint; the problem tooltip is HTML that
     * drops the newline, so the hint goes on its own line, with the `code`
     * spans of the hint rendered as code and the check's name at the end.
     * The one-line message stays the message (Problems view, status bar).
     */
    override val lspDiagnosticsSupport: LspDiagnosticsSupport = object : LspDiagnosticsSupport() {
        override fun getMessage(diagnostic: Diagnostic): String =
            diagnostic.message.lineSequence().firstOrNull()?.trim() ?: diagnostic.message

        override fun getTooltip(diagnostic: Diagnostic): String {
            val lines = diagnostic.message.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val body = StringBuilder()
            lines.forEachIndexed { i, line ->
                if (i > 0) body.append("<br>")
                body.append(inlineCode(line))
            }
            val code = diagnostic.code?.let { if (it.isLeft) it.left else it.right?.toString() }
            if (diagnostic.source == "adm-lint" && !code.isNullOrBlank()) {
                body.append("<br><span style='color: gray;'>adm lint: ").append(escape(code)).append("</span>")
            }
            return "<html>$body</html>"
        }

        private fun escape(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /** Backtick spans become code; everything else is escaped text. */
        private fun inlineCode(line: String): String {
            val out = StringBuilder()
            val parts = line.split('`')
            parts.forEachIndexed { i, part ->
                if (i % 2 == 1 && i < parts.size - 1) out.append("<code>").append(escape(part)).append("</code>")
                else out.append(escape(part))
            }
            return out.toString()
        }
    }

    /**
     * What the server reads at `initialize`: whether to lint the open files
     * and which checks to leave out. The check switches are the Lint tab's
     * (per project), so the tab and the editor agree.
     */
    override fun createInitializationOptions(): Any {
        val props = com.intellij.ide.util.PropertiesComponent.getInstance(project)
        val skip = ADMLintCodes.ALL.filter { props.getBoolean("ADM.Lint.skip.$it", false) }
        return mapOf("lint" to mapOf("enabled" to ADMSettingsState.getInstance().lintInEditor, "skip" to skip))
    }

    /**
     * Presentation computed from server answers (gutter markers, usage
     * counts) is cached by the daemon until the document changes; a server
     * (re)start changes no document, so nudge the daemon ourselves.
     */
    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverInitialized(params: InitializeResult) {
            ADMServerRefresh.restartDaemon(project)
        }
    }

    override fun createCommandLine(): GeneralCommandLine {
        val workDir = project.basePath?.let(Path::of)
        val admBinary = ADMExec.resolve(project.basePath)
            ?: throw ExecutionException(
                "ADM LSP requires a `adm` binary on PATH or under ${workDir ?: "the project directory"} (bin/adm or build/adm)"
            )
        val args = mutableListOf("lsp")
        if (ADMSettingsState.getInstance().logLspProtocol) {
            // Traces every request and response to stderr, which the LSP client
            // folds into the IDE log -- the only way to see whether a request
            // reached the server at all.
            args.add("--log-protocol")
        }
        val cmd = GeneralCommandLine(admBinary.toString(), *args.toTypedArray())
        workDir?.let { cmd.withWorkDirectory(it.toFile()) }
        cmd.withEnvironment(ADMExec.env())
        LOG.info("Launching ADM LSP via `${admBinary}` in workDir=${cmd.workDirectory}")
        ADMLspLogs.logToFile(project, "Launching ADM LSP via `${admBinary}` workDir=${cmd.workDirectory} PATH=${System.getenv("PATH")}")
        return cmd
    }
}

object ADMLspLocator {
    fun findADMExecutable(workDir: Path?): Path? {
        findInPath("adm")?.let {
            LOG.info("Found adm executable on PATH: $it")
            return it
        }
        homeADM()?.let {
            LOG.info("Found adm executable under home: $it")
            return it
        }
        if (workDir != null) {
            val candidates = listOf(
                workDir.resolve("build/adm"),
                workDir.resolve("bin/adm")
            )
            for (candidate in candidates) {
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    LOG.info("Found adm executable in project: $candidate")
                    return candidate
                }
            }
        }
        LOG.warn("ADM executable not found on PATH or under project; LSP will not start")
        return null
    }

    private fun findInPath(executable: String): Path? {
        val pathEnv = System.getenv("PATH") ?: return null
        val pathext = System.getenv("PATHEXT")?.split(File.pathSeparatorChar, ';')?.filter { it.isNotBlank() }
        val extensions = when {
            pathext.isNullOrEmpty() -> listOf("")
            else -> pathext.map { if (it.startsWith(".")) it else ".$it" }
        }
        pathEnv.split(File.pathSeparator).forEach { dir ->
            if (dir.isBlank()) return@forEach
            val base = Paths.get(dir)
            for (ext in extensions) {
                val candidate = base.resolve(executable + ext)
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun homeADM(): Path? {
        val home = System.getProperty("user.home") ?: return null
        val candidate = Paths.get(home).resolve(".adm").resolve("bin").resolve("adm")
        return candidate.takeIf { Files.isRegularFile(it) && Files.isExecutable(it) }
    }
}

object ADMLspLogs {
    fun logToFile(project: Project, message: String) {
        return
//        val base = project.basePath?.let { Paths.get(it) } ?: Paths.get(System.getProperty("java.io.tmpdir"))
//        val logPath = base.resolve("adm-intellij.log")
//        val line = "[${Instant.now()}] $message\n"
//        try {
//            Files.write(
//                logPath,
//                line.toByteArray(StandardCharsets.UTF_8),
//                StandardOpenOption.CREATE,
//                StandardOpenOption.APPEND
//            )
//        } catch (e: Exception) {
//            LOG.warn("Failed to write LSP marker log to $logPath: ${e.message}")
//        }
    }
}

/** The lint checks, as `adm lint --list` names them; the Lint tab's switches key on these. */
object ADMLintCodes {
    // Mirrors lint.Codes in internal/lint/lint.go.
    val ALL = listOf(
        "ref-cycle", "missing-dispose", "unsynchronised-field", "dropped-future", "blocking-observer",
        "unchecked-map-index", "unreachable", "empty-onerror", "self-assign", "constant-condition",
        "unused-local", "shadow", "unused-import", "unused-internal", "error-doc", "missing-doc",
        "naming", "accessor-prefix", "todo", "undeclared-dependency", "undeclared-permission", "service-dependency-cycle",
        "assertless-test", "untested-export", "suite-file-without-suite", "prefer-lambda",
        "prefer-expects", "prefer-when", "prefer-junction", "prefer-empty-block",
    )
}
