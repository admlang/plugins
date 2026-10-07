package org.adm.intellij.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import org.adm.intellij.settings.ADMSettingsState
import java.io.File
import java.nio.charset.StandardCharsets
import java.lang.reflect.InvocationTargetException

object ADMDebuggerActions {
	private val log = Logger.getInstance(ADMDebuggerActions::class.java)

	/**
	 * Starts a debug session for a run configuration. This is the entry point
	 * the debug [ADMDebugProgramRunner] uses, so the session is owned by a real
	 * ExecutionEnvironment: Rerun re-enters here, and the configuration's
	 * environment variables and program arguments are honoured.
	 *
	 * The kind is read off the `adm` subcommand the configuration carries --
	 * `run` for an application, `test` for a test or benchmark -- which is the
	 * same shape [ADMRunMarkers] produces.
	 */
	fun debugConfiguration(configuration: ADMRunConfiguration, environment: ExecutionEnvironment? = null) {
		val project = configuration.project
		val args = configuration.args
		if (args.isEmpty()) {
			Messages.showErrorDialog(project, "ADM debug configuration has no arguments.", "ADM")
			return
		}

		val workDir = configuration.workDir ?: project.basePath
		if (workDir.isNullOrBlank()) {
			Messages.showErrorDialog(project, "Cannot determine working directory for ADM debug.", "ADM")
			return
		}

		val env = LinkedHashMap(configuration.effectiveEnv())
		// The ADM tool window inspects the debuggee the same way it inspects a
		// plain run: give the process an endpoint port and key it on the
		// session's environment (the debug bridge owns the process handler).
		val idePort = org.adm.intellij.ide.ADMInspect.pickFreePort()
		env["ADM_IDE_PORT"] = idePort.toString()
		if (environment != null) {
			org.adm.intellij.ide.ADMInspect.register(environment, idePort)
		}
		org.adm.intellij.ide.ADMInspect.setPendingDebugPort(idePort)
		when (args.first().trim().lowercase()) {
			"run" -> debugApplication(project, configuration.name, workDir, args, configuration.appArgs, env, environment)
			"test" -> {
				// `adm test <file> ...` -- the file the suite lives in is the first
				// positional, and the runner needs it to scope `--file`.
				val filePath = args.getOrNull(1)?.trim().orEmpty()
				if (filePath.isEmpty()) {
					Messages.showErrorDialog(project, "Cannot debug: ADM test configuration has no source file.", "ADM")
					return
				}
				debugTestOrBench(project, configuration.name, workDir, filePath, args, env, environment)
			}
			else -> Messages.showErrorDialog(
				project,
				"Cannot debug `adm ${args.first()}`: only `run` and `test` configurations are debuggable.",
				"ADM",
			)
		}
	}

	fun debugMarker(marker: ADMRunMarkers.Marker, file: PsiFile) {
		val project = file.project
		val vf = file.virtualFile
		if (vf == null) return

		val workDir = vf.parent?.path ?: project.basePath
		if (workDir.isNullOrBlank()) return

		when (marker.kind) {
			ADMRunMarkers.Kind.Application -> debugApplication(project, marker.title, workDir, marker.args, emptyList(), ADMExec.env(), null)
			ADMRunMarkers.Kind.Test, ADMRunMarkers.Kind.Suite, ADMRunMarkers.Kind.Benchmark -> debugTestOrBench(project, marker.title, workDir, vf.path, marker.args, ADMExec.env(), null)
			// Plugin and library markers build an archive; there is nothing to debug.
			ADMRunMarkers.Kind.Plugin, ADMRunMarkers.Kind.Library -> return
		}
	}

	private fun debugApplication(
		project: com.intellij.openapi.project.Project,
		title: String,
		_workDir: String,
		args: List<String>,
		appArgs: List<String>,
		env: Map<String, String>,
		environment: ExecutionEnvironment?,
	) {
		val appName = args.getOrNull(1)?.trim().orEmpty()
		if (appName.isEmpty()) {
			Messages.showErrorDialog(project, "Cannot debug: missing application name.", "ADM")
			return
		}

		val pathIdx = args.indexOf("--path")
		val sourcePath = if (pathIdx >= 0) args.getOrNull(pathIdx + 1)?.trim().orEmpty() else ""
		if (sourcePath.isEmpty()) {
			Messages.showErrorDialog(project, "Cannot debug: missing --path.", "ADM")
			return
		}

		val bridge = try {
			Class.forName("org.adm.intellij.debugger.ADMDebuggerBridge")
		} catch (_: Throwable) {
			Messages.showErrorDialog(project, "Native debugging is not available (missing com.intellij.nativeDebug).", "ADM")
			return
		}

		val admCacheDir = ADMExec.defaultCacheDir().trim()
		val lldbPath = ADMSettingsState.getInstance().lldbExecutablePath.trim()

		try {
			val debugTitle = title.replace("run", "debug")

			runCatching {
				// Fullest form first: carries the selected backend and the run
				// configuration's environment. Older bridge jars lack it, so the
				// shorter signatures below remain as fallbacks.
				val m = bridge.getMethod(
					"debugADMApp",
					com.intellij.openapi.project.Project::class.java,
					String::class.java,
					String::class.java,
					String::class.java,
					List::class.java,
					String::class.java,
					String::class.java,
					String::class.java,
					Map::class.java,
					ExecutionEnvironment::class.java,
				)
				m.invoke(null, project, debugTitle, sourcePath, appName, appArgs, admCacheDir, lldbPath, ADMExec.backend().id, env, environment)
			}.recoverCatching { _ ->
				val m = bridge.getMethod(
					"debugADMApp",
					com.intellij.openapi.project.Project::class.java,
					String::class.java,
					String::class.java,
					String::class.java,
					List::class.java,
					String::class.java,
					String::class.java,
				)
				m.invoke(null, project, debugTitle, sourcePath, appName, appArgs, admCacheDir, lldbPath)
			}.getOrElse { _ ->
				val m = bridge.getMethod(
					"debugADMApp",
					com.intellij.openapi.project.Project::class.java,
					String::class.java,
					String::class.java,
					String::class.java,
					List::class.java,
				)
				m.invoke(null, project, debugTitle, sourcePath, appName, appArgs)
			}
		} catch (t: InvocationTargetException) {
			val cause = t.targetException ?: t.cause ?: t
			log.warn("Failed to start debugger (invocation target)", cause)
			Messages.showErrorDialog(project, "Failed to start debugger: ${cause.message ?: cause.javaClass.simpleName}", "ADM")
		} catch (t: Throwable) {
			log.warn("Failed to start debugger", t)
			Messages.showErrorDialog(project, "Failed to start debugger: ${t.message ?: t.javaClass.simpleName}", "ADM")
		}
	}

	private fun debugTestOrBench(
		project: com.intellij.openapi.project.Project,
		title: String,
		workDir: String,
		filePath: String,
		args: List<String>,
		env: Map<String, String>,
		environment: ExecutionEnvironment?,
	) {
		val adm = ADMExec.resolve(project.basePath)
		if (adm == null) {
			Messages.showErrorDialog(project, "Could not locate the `adm` executable for debugging.", "ADM")
			return
		}

		val cacheDir = ADMExec.defaultCacheDir().trim()
		if (cacheDir.isBlank()) {
			Messages.showErrorDialog(project, "Cannot determine ADM_CACHE for debugging. Configure it in ADM settings.", "ADM")
			return
		}

		val outDir = File(cacheDir)
			.resolve("idea-debug")
			.resolve("test")
			.resolve(System.currentTimeMillis().toString())
		if (!outDir.mkdirs() && !outDir.isDirectory) {
			Messages.showErrorDialog(project, "Failed to create debug output dir: ${outDir.absolutePath}", "ADM")
			return
		}

		val buildArgs = ArrayList(ADMExec.withBackend(args))
		// Builds are optimized by default; the debugger needs -O0 -g.
		buildArgs.addAll(listOf("--out", outDir.absolutePath, "--build-only", "--debug"))

		val cmd = GeneralCommandLine(adm.toString())
			.withWorkDirectory(workDir)
			.withCharset(StandardCharsets.UTF_8)
			.withEnvironment(env)
			.withParameters(buildArgs)

		// The test-runner build must not run on the EDT (it takes seconds to
		// minutes and the platform flags waitFor there); build on a pooled
		// thread, then start the session back on the EDT.
		com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
			val output = try {
				CapturingProcessHandler(cmd).runProcess(10 * 60 * 1000)
			} catch (t: Throwable) {
				log.warn("Failed to build test runner for debugging", t)
				com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
					Messages.showErrorDialog(project, "Failed to build test runner: ${t.message ?: t.javaClass.simpleName}", "ADM")
				}
				return@executeOnPooledThread
			}
			com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
				if (output.exitCode != 0) {
					Messages.showErrorDialog(project, "adm test build failed (exit ${output.exitCode}).\n\n${output.stderr.ifBlank { output.stdout }}", "ADM")
				} else {
					startTestDebugSession(project, title, workDir, filePath, args, env, environment, output.stdout + "\n" + output.stderr, outDir)
				}
			}
		}
	}

	private fun startTestDebugSession(
		project: com.intellij.openapi.project.Project,
		title: String,
		workDir: String,
		filePath: String,
		args: List<String>,
		env: Map<String, String>,
		environment: ExecutionEnvironment?,
		buildOutput: String,
		outDir: File,
	) {
		val admTestBin = parseADMTestBin(buildOutput)
			.ifBlank { File(outDir, "adm_test_bin").absolutePath }

		val runnerArgs = extractRunnerArgs(args, filePath)
		val lldbPath = ADMSettingsState.getInstance().lldbExecutablePath.trim()
		val admCacheDir = ADMExec.defaultCacheDir().trim()

		val bridge = try {
			Class.forName("org.adm.intellij.debugger.ADMDebuggerBridge")
		} catch (_: Throwable) {
			Messages.showErrorDialog(project, "Native debugging is not available (missing com.intellij.nativeDebug).", "ADM")
			return
		}

		val debugTitle = title.replace("test", "debug").replace("bench", "debug")
		try {
			runCatching {
				// Fullest form: carries the configuration's environment and the
				// ExecutionEnvironment the session must belong to for Rerun.
				val m = bridge.getMethod(
					"debugADMTestBinary",
					com.intellij.openapi.project.Project::class.java,
					String::class.java,
					String::class.java,
					String::class.java,
					List::class.java,
					String::class.java,
					String::class.java,
					Map::class.java,
					ExecutionEnvironment::class.java,
				)
				m.invoke(null, project, debugTitle, workDir, admTestBin, runnerArgs, admCacheDir, lldbPath, env, environment)
			}.getOrElse { _ ->
				val m = bridge.getMethod(
					"debugADMTestBinary",
					com.intellij.openapi.project.Project::class.java,
					String::class.java,
					String::class.java,
					String::class.java,
					List::class.java,
					String::class.java,
					String::class.java,
				)
				m.invoke(null, project, debugTitle, workDir, admTestBin, runnerArgs, admCacheDir, lldbPath)
			}
		} catch (t: InvocationTargetException) {
			val cause = t.targetException ?: t.cause ?: t
			log.warn("Failed to start debugger (invocation target)", cause)
			Messages.showErrorDialog(project, "Failed to start debugger: ${cause.message ?: cause.javaClass.simpleName}", "ADM")
		} catch (t: Throwable) {
			log.warn("Failed to start debugger", t)
			Messages.showErrorDialog(project, "Failed to start debugger: ${t.message ?: t.javaClass.simpleName}", "ADM")
		}
	}

	private fun parseADMTestBin(output: String): String {
		val prefix = "ADM_TEST_BIN="
		for (line in output.lineSequence()) {
			val trimmed = line.trim()
			if (trimmed.startsWith(prefix)) {
				return trimmed.removePrefix(prefix).trim()
			}
		}
		return ""
	}

	private fun extractRunnerArgs(args: List<String>, filePath: String): List<String> {
		val out = ArrayList<String>()
		var i = 0
		while (i < args.size) {
			val a = args[i]
			when (a) {
				"--run", "--file", "--bench", "--bench-time", "--bench-count" -> {
					val v = args.getOrNull(i + 1)
					if (v != null) {
						out.add(a)
						out.add(v)
						i += 2
						continue
					}
				}
			}
			i++
		}
		if (!out.contains("--file")) {
			out.addAll(listOf("--file", "=$filePath"))
		}
		return out
	}
}
