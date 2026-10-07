package org.adm.intellij.debugger

import com.intellij.execution.Executor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.TextConsoleBuilder
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.util.system.CpuArch
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.jetbrains.cidr.ArchitectureType
import com.jetbrains.cidr.execution.Installer
import com.jetbrains.cidr.execution.RunParameters
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriverConfiguration
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriver
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriverConfiguration
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object ADMDebuggerBridge {
	private val log = Logger.getInstance(ADMDebuggerBridge::class.java)
	private val admIcon = runCatching { IconLoader.getIcon("/icons/adm.svg", ADMDebuggerBridge::class.java) }.getOrNull()

	@JvmStatic
	fun debugADMApp(project: Project, title: String, sourcePath: String, appName: String, appArgs: List<String>) {
		debugADMApp(project, title, sourcePath, appName, appArgs, null)
	}

	@JvmStatic
	fun debugADMApp(project: Project, title: String, sourcePath: String, appName: String, appArgs: List<String>, admCacheDir: String?) {
		debugADMApp(project, title, sourcePath, appName, appArgs, admCacheDir, null)
	}

	@JvmStatic
	fun debugADMApp(
		project: Project,
		title: String,
		sourcePath: String,
		appName: String,
		appArgs: List<String>,
		admCacheDir: String?,
		lldbPath: String?,
	) {
		debugADMApp(project, title, sourcePath, appName, appArgs, admCacheDir, lldbPath, null, null, null)
	}

	/**
	 * Full form. [backend] selects the compiler backend for the debuggable
	 * build, as picked in settings.
	 * [extraEnv] carries the run configuration's own environment variables.
	 */
	@JvmStatic
	fun debugADMApp(
		project: Project,
		title: String,
		sourcePath: String,
		appName: String,
		appArgs: List<String>,
		admCacheDir: String?,
		lldbPath: String?,
		backend: String?,
		extraEnv: Map<String, String>?,
		callerEnvironment: ExecutionEnvironment?,
	) {
		val adm = resolveADMExecutable()
		if (adm == null) {
			Messages.showErrorDialog(project, "Could not locate the `adm` executable for debugging.", "ADM")
			return
		}

		val cacheRoot = resolveDebugRootDir(admCacheDir)
		val buildOut = cacheRoot
			.resolve("run")
			.resolve(sanitizeName(appName))
			.resolve(System.currentTimeMillis().toString())
		if (!buildOut.mkdirs() && !buildOut.isDirectory) {
			Messages.showErrorDialog(project, "Failed to create debug output dir: ${buildOut.absolutePath}", "ADM")
			return
		}

		val env = admEnv().also { m ->
			val cache = admCacheDir?.trim().orEmpty()
			if (cache.isNotBlank()) m["ADM_CACHE"] = cache
			extraEnv?.let(m::putAll)
		}

		val buildArgs = ArrayList(
			listOf(
				"build",
				"--apps",
				appName,
				"--path",
				sourcePath,
				"--out",
				buildOut.absolutePath,
				"--debug",
				"--no-cache",
			)
		)
		backend?.trim()?.takeIf { it.isNotEmpty() }?.let { buildArgs.addAll(listOf("--backend", it)) }

		val binary = File(buildOut, sanitizeName(appName))
		val console = ConsoleViewImpl(project, true)
		console.print("Building debuggable binary…\n", ConsoleViewContentType.SYSTEM_OUTPUT)

		// The build takes seconds to minutes and must not run on the EDT (the
		// platform flags OSProcessHandler.waitFor there, and the UI would
		// freeze for the duration). Build on a pooled thread; the debug
		// session itself starts back on the EDT.
		com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
			val failure: String? = try {
				val cmd = GeneralCommandLine(adm)
					.withWorkDirectory(sourcePath)
					.withCharset(StandardCharsets.UTF_8)
					.withParameters(buildArgs)
					.withEnvironment(env)
				val out = CapturingProcessHandler(cmd).runProcess(10 * 60 * 1000)
				if (out.stdout.isNotBlank()) console.print(out.stdout, ConsoleViewContentType.NORMAL_OUTPUT)
				if (out.stderr.isNotBlank()) console.print(out.stderr, ConsoleViewContentType.ERROR_OUTPUT)
				when {
					out.exitCode != 0 -> "adm build failed (exit ${out.exitCode}). See Debug console output."
					!binary.exists() -> "Built binary not found: ${binary.absolutePath}"
					else -> null
				}
			} catch (t: Throwable) {
				log.warn("Failed to build debuggable binary", t)
				"Failed to build debuggable binary: ${t.message ?: t.javaClass.simpleName}"
			}

			com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
				if (failure != null) {
					Messages.showErrorDialog(project, failure, "ADM")
				} else {
					debugBinary(project, title, sourcePath, binary.absolutePath, appArgs, env, console, cacheRoot, lldbPath, callerEnvironment)
				}
			}
		}
	}

	@JvmStatic
	fun debugADMTestBinary(project: Project, title: String, workDir: String, admTestBin: String, args: List<String>) {
		debugADMTestBinary(project, title, workDir, admTestBin, args, null)
	}

	@JvmStatic
	fun debugADMTestBinary(project: Project, title: String, workDir: String, admTestBin: String, args: List<String>, lldbPath: String?) {
		debugADMTestBinary(project, title, workDir, admTestBin, args, null, lldbPath)
	}

	@JvmStatic
	fun debugADMTestBinary(
		project: Project,
		title: String,
		workDir: String,
		admTestBin: String,
		args: List<String>,
		admCacheDir: String?,
		lldbPath: String?,
	) {
		debugADMTestBinary(project, title, workDir, admTestBin, args, admCacheDir, lldbPath, null, null)
	}

	/**
	 * Full form. [extraEnv] carries the run configuration's own environment
	 * variables, and [callerEnvironment] the ExecutionEnvironment the session
	 * should belong to -- see debugBinary on why Rerun depends on it.
	 */
	@JvmStatic
	fun debugADMTestBinary(
		project: Project,
		title: String,
		workDir: String,
		admTestBin: String,
		args: List<String>,
		admCacheDir: String?,
		lldbPath: String?,
		extraEnv: Map<String, String>?,
		callerEnvironment: ExecutionEnvironment?,
	) {
		val cacheRoot = resolveDebugRootDir(admCacheDir)
		val env = admEnv().also { m -> extraEnv?.let(m::putAll) }
		debugBinary(project, title, workDir, admTestBin, args, env, ConsoleViewImpl(project, true), cacheRoot, lldbPath, callerEnvironment)
	}

	private fun debugBinary(
		project: Project,
		title: String,
		workDir: String,
		binaryPath: String,
		args: List<String>,
		env: Map<String, String>,
		console: ConsoleViewImpl,
		cacheRoot: File,
		lldbPath: String?,
		callerEnvironment: ExecutionEnvironment?,
	) {
		val driver = object : LLDBDriverConfiguration() {
			override fun getDriverName(): String = "ADM LLDB"

			// ADM arrays are runtime handles (`{data, len, cap, ...}`), and DWARF cannot
			// say "data points at len elements" when the count is a sibling member — so
			// array CONTENTS need an LLDB formatter. The driver runs
			// `command script import "<path>"` for the autorun script, which is exactly
			// how adm_formatters.py registers its synthetic children.
			override fun createDriver(handler: DebuggerDriver.Handler, arch: ArchitectureType): LLDBDriver {
				val d = super.createDriver(handler, arch)
				ADMLldbFormatters.script()?.let { script ->
					try {
						d.setAutorunScriptName(script.absolutePath)
					} catch (t: Throwable) {
						log.warn("Failed to register ADM LLDB formatters", t)
					}
				}
				return d
			}
		}
		if (!ensureUsableLLDB(driver, project, cacheRoot, lldbPath)) {
			return
		}
		val parameters = ADMRunParameters(driver, workDir, binaryPath, args, env)

		val profile = object : RunProfile {
			override fun getName(): String = title
			override fun getIcon() = admIcon
			override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null
		}

		// Attach the session to the environment the caller was invoked with, when
		// there is one. That environment belongs to a registered runner and a real
		// run configuration, which is what the Debug tool window's Rerun button
		// re-executes. Building a fresh environment here instead -- around the
		// throwaway `profile` and the no-op ADMDebugRunner below -- left Rerun
		// re-entering a runner whose `execute` does nothing, so it appeared dead.
		// The local fallback keeps the legacy gutter path working when the bridge
		// is called without an environment.
		val environment = callerEnvironment ?: ExecutionEnvironmentBuilder(
			project,
			DefaultDebugExecutor.getDebugExecutorInstance(),
		)
			.runProfile(profile)
			.runner(ADMDebugRunner)
			.build()
		val consoleBuilder = object : TextConsoleBuilder() {
			override fun getConsole() = console
			override fun addFilter(filter: Filter) {}
			override fun setViewer(isViewer: Boolean) {}
		}

		XDebuggerManager.getInstance(project).startSessionAndShowTab(title, object : XDebugProcessStarter() {
			override fun start(session: XDebugSession): XDebugProcess {
				val proc = ADMLocalDebugProcess(parameters, session, consoleBuilder)
				// The console handed to CIDR was built here for the build log, so it is
				// not wired to the debug process — without this, the program's own
				// output (println) never reaches the Debug console, unlike a plain Run.
				try {
					console.attachToProcess(proc.processHandler)
				} catch (t: Throwable) {
					log.warn("Failed to attach console to debug process", t)
				}
				ProcessTerminatedListener.attach(proc.processHandler, project)
				proc.start()
				return proc
			}
		}, environment)
	}

	private class ADMRunParameters(
		private val driverConfiguration: DebuggerDriverConfiguration,
		private val workDir: String,
		private val binaryPath: String,
		private val args: List<String>,
		private val env: Map<String, String>,
	) : RunParameters() {
		override fun getDebuggerDriverConfiguration(): DebuggerDriverConfiguration = driverConfiguration

		override fun getArchitectureId(): String {
			return ArchitectureType.forVmCpuArch(CpuArch.CURRENT).id
		}

		override fun getInstaller(): Installer {
			return ADMBinaryInstaller(workDir, binaryPath, args, env)
		}
	}

	private class ADMBinaryInstaller(
		private val workDir: String,
		private val binaryPath: String,
		private val args: List<String>,
		private val env: Map<String, String>,
	) : Installer {
		override fun install(): GeneralCommandLine {
			return GeneralCommandLine(binaryPath)
				.withWorkDirectory(workDir)
				.withCharset(StandardCharsets.UTF_8)
				.withEnvironment(env)
				.withParameters(args)
				.withRedirectErrorStream(true)
		}

		override fun getExecutableFile(): File {
			return File(binaryPath)
		}
	}

	private fun sanitizeName(s: String): String {
		val b = StringBuilder(s.length)
		for (ch in s) {
			if (ch.isLetterOrDigit()) b.append(ch) else b.append('_')
		}
		return b.toString().ifBlank { "app" }
	}

	private object ADMDebugRunner : ProgramRunner<com.intellij.execution.configurations.RunnerSettings> {
		override fun getRunnerId(): String = "ADMDebugRunner"
		override fun canRun(executorId: String, profile: RunProfile): Boolean = true
		override fun execute(environment: ExecutionEnvironment) {}
	}

	private fun ensureUsableLLDB(driver: LLDBDriverConfiguration, project: Project, cacheRoot: File, lldbOverride: String?): Boolean {
		// The nativeDebug plugin doesn't always ship with a bundled LLDB in IntelliJ IDEA,
		// so we try to point LLDB at a working distribution.
		val candidates = ArrayList<String>()
		var lastError: Throwable? = null

		lldbOverride?.trim()?.takeIf { it.isNotEmpty() }?.let(candidates::add)

		System.getenv("ADM_LLDB")?.trim()?.takeIf { it.isNotEmpty() }?.let(candidates::add)
		System.getenv("ADM_LLDB_PATH")?.trim()?.takeIf { it.isNotEmpty() }?.let(candidates::add)

		// Prefer LLVM-prefixed installations since JetBrains' LLDB integration expects `../lib/liblldb.so`
		// to exist relative to the `lldb` binary (e.g. `/usr/lib/llvm-17/bin/lldb`).
		candidates.addAll(findSystemLlvmLlbdCandidates())

		candidates.add("/usr/bin/lldb")
		candidates.add("/usr/sbin/lldb")
		candidates.add("/bin/lldb")

		val arch = when (CpuArch.CURRENT) {
			CpuArch.ARM64 -> "aarch64"
			else -> "x64"
		}
		val pluginsPath = runCatching { PathManager.getPluginsPath() }.getOrNull().orEmpty()
		if (pluginsPath.isNotBlank()) {
			candidates.add(File(pluginsPath).resolve("nativeDebug-plugin/bin/lldb/linux/$arch/bin/lldb").absolutePath)
		}

		// Fall back to PATH.
		for (dir in System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)) {
			if (dir.isBlank()) continue
			val lldb = File(dir, "lldb")
			if (lldb.isFile && lldb.canExecute()) candidates.add(lldb.absolutePath)
			val lldbVscode = File(dir, "lldb-vscode")
			if (lldbVscode.isFile && lldbVscode.canExecute()) candidates.add(lldbVscode.absolutePath)
		}

		for (path in candidates.distinct()) {
			val f0 = File(path)
			if (!f0.isFile || !f0.canExecute()) continue
			val f = runCatching { f0.canonicalFile }.getOrDefault(f0)
			try {
				val prepared = prepareLldbForJetBrains(cacheRoot, f) ?: continue
				driver.setCustomLLDBPath(prepared.absolutePath)
				// Force an early validation pass so we fail here (with a usable message) instead of later.
				driver.readVersion()
				log.info("ADM debugger: using LLDB (${f.name})")
				return true
			} catch (t: Throwable) {
				log.warn("ADM debugger: LLDB candidate failed (${f.name})", t)
				lastError = t
			}
		}

		val lastMsg = lastError?.message?.takeIf { it.isNotBlank() } ?: lastError?.javaClass?.simpleName

		Messages.showErrorDialog(
			project,
			buildString {
				appendLine("LLDB was not found or could not be loaded.")
				if (lastMsg != null) {
					appendLine()
					appendLine("Last error: $lastMsg")
				}
				appendLine()
				appendLine("Fix:")
				appendLine("  - Point the LLDB setting to a compatible `lldb` (often under `/usr/lib/llvm-*/bin/lldb`), or")
				appendLine("  - Configure an LLDB path manually, or")
				appendLine("  - Set `ADM_LLDB=/path/to/lldb` and restart IntelliJ (or set it in ADM settings).")
			},
			"ADM"
		)
		return false
	}

	private fun prepareLldbForJetBrains(cacheRoot: File, lldb: File): File? {
		// Custom LLDB support expects `../lib/liblldb.so` relative to the lldb executable.
		val lldbBin = lldb
		val liblldb = findLibLldbFor(lldbBin) ?: return lldbBin // let driver try; it will raise a useful error.

		val direct = lldbBin.parentFile?.parentFile
			?.resolve("lib")
			?.resolve("liblldb.so")
		if (direct != null && direct.isFile) return lldbBin

		// Create a small shim layout under ADM_CACHE so the expected relative paths exist.
		val shimRoot = cacheRoot.resolve("lldb-shim").resolve(hashPath(lldbBin.absolutePath + "\n" + liblldb.absolutePath))
		val binDir = shimRoot.resolve("bin")
		val libDir = shimRoot.resolve("lib")
		if (!binDir.exists() && !binDir.mkdirs()) return null
		if (!libDir.exists() && !libDir.mkdirs()) return null

		val shimLldb = binDir.resolve("lldb")
		val shimLib = libDir.resolve("liblldb.so")
		val shimLibVersioned = libDir.resolve(liblldb.name)

		try {
			// Do NOT symlink the lldb binary: JetBrains resolves the custom LLDB path to a real path,
			// and a symlink would collapse back to `/usr/bin/lldb`, breaking the expected `../lib` layout.
			writeExecWrapper(shimLldb, lldbBin)
			// JetBrains searches for `liblldb.so.*` in the custom `lib` directory, so ensure that exists.
			createSymlinkOrCopy(shimLibVersioned, liblldb)
			// Also provide `liblldb.so` for tools that look for the unversioned SONAME.
			createSymlinkOrCopy(shimLib, liblldb)
		} catch (t: Throwable) {
			log.warn("Failed to prepare LLDB shim", t)
			return null
		}

		return shimLldb
	}

	private fun findLibLldbFor(lldbBin: File): File? {
		val root = lldbBin.parentFile?.parentFile ?: return null
		val candidates = ArrayList<File>()
		// (the trailing dot variants are just to bias ordering in the list; they are not real files)
		candidates.add(root.resolve("lib").resolve("liblldb.so"))
		candidates.add(root.resolve("lib64").resolve("liblldb.so"))

		fun addGlob(dir: File) {
			val entries = dir.listFiles() ?: return
			for (e in entries) {
				if (!e.isFile) continue
				if (e.name.startsWith("liblldb.so.")) candidates.add(e)
				if (e.name == "liblldb.so") candidates.add(e)
			}
		}

		addGlob(root.resolve("lib"))
		addGlob(root.resolve("lib64"))
		addGlob(File("/lib64"))
		addGlob(File("/usr/lib64"))
		addGlob(File("/lib"))
		addGlob(File("/usr/lib"))

		// Prefer a versioned library (`liblldb.so.<ver>`) because that's what JetBrains searches for.
		return candidates.firstOrNull { it.isFile && it.name.startsWith("liblldb.so.") }
			?: candidates.firstOrNull { it.isFile && it.name == "liblldb.so" }
	}

	private fun createSymlinkOrCopy(dst: File, src: File) {
		if (dst.exists()) return
		runCatching {
			java.nio.file.Files.createSymbolicLink(dst.toPath(), src.toPath())
		}.getOrElse {
			java.nio.file.Files.copy(src.toPath(), dst.toPath())
		}
	}

	private fun writeExecWrapper(dst: File, target: File) {
		if (dst.exists()) return

		val script = buildString {
			appendLine("#!/bin/sh")
			append("exec ")
			append(escapeSh(target.absolutePath))
			append(" \"$@\"")
			appendLine()
		}

		java.nio.file.Files.writeString(dst.toPath(), script, Charsets.UTF_8)
		dst.setExecutable(true, false)
	}

	private fun escapeSh(s: String): String {
		// Single-quote shell escaping: ' -> '"'"'
		return "'" + s.replace("'", "'\"'\"'") + "'"
	}

	private fun hashPath(s: String): String {
		val md = MessageDigest.getInstance("SHA-256")
		val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
		val hex = StringBuilder(bytes.size * 2)
		for (b in bytes) hex.append(((b.toInt() shr 4) and 0xF).toString(16)).append((b.toInt() and 0xF).toString(16))
		return hex.toString()
	}

	private fun findSystemLlvmLlbdCandidates(): List<String> {
		val roots = listOf(
			File("/usr/lib"),
			File("/usr/lib64"),
			File("/usr/local/lib"),
			File("/usr/local/lib64"),
			File("/opt"),
		)
		val bins = ArrayList<Pair<Int, File>>()

		for (root in roots) {
			val entries = root.listFiles() ?: continue
			for (e in entries) {
				if (!e.isDirectory) continue
				val name = e.name
				if (!name.startsWith("llvm")) continue
				val verStr = name.removePrefix("llvm").removePrefix("-").takeWhile { it.isDigit() }
				val ver = verStr.toIntOrNull() ?: continue
				val lldb = e.resolve("bin").resolve("lldb")
				if (lldb.isFile && lldb.canExecute()) {
					bins.add(ver to lldb)
				}
			}
		}

		// Highest LLVM first.
		bins.sortByDescending { it.first }
		return bins.map { it.second.absolutePath }
	}

	private fun admEnv(): MutableMap<String, String> {
		return HashMap(System.getenv())
	}

	private fun resolveADMExecutable(): String? {
		val path = System.getenv("PATH").orEmpty()
		for (dir in path.split(File.pathSeparatorChar)) {
			if (dir.isBlank()) continue
			val candidate = File(dir, "adm")
			if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
		}

		val home = System.getProperty("user.home").orEmpty()
		val candidates = listOf(
			"$home/.adm/bin/adm",
			"$home/.local/bin/adm",
			"$home/bin/adm",
			"/usr/local/bin/adm",
			"/usr/bin/adm",
		)
		return candidates.firstOrNull { File(it).isFile }
	}

	private fun resolveDebugRootDir(admCacheDir: String?): File {
		val cache = admCacheDir?.trim().orEmpty().ifBlank { System.getenv("ADM_CACHE").orEmpty().trim() }
		if (cache.isNotBlank()) return File(cache).resolve("idea-debug")

		val home = System.getProperty("user.home").orEmpty()
		if (home.isNotBlank()) return File(home).resolve(".adm").resolve("cache").resolve("idea-debug")
		return File(System.getProperty("java.io.tmpdir")).resolve("adm-idea-debug")
	}
}
