package org.adm.intellij.ide

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import org.adm.intellij.run.ADMExec
import java.nio.charset.StandardCharsets

/**
 * Runs `adm` for the tool window. Every panel talks to the compiler through
 * its commands and reads their `--json` output, so the panel never scrapes
 * human-readable text and never re-implements what a command already does.
 */
object ADMCli {
	/** The outcome of one command: exit code plus captured streams. */
	class Result(val exitCode: Int, val stdout: String, val stderr: String) {
		val ok: Boolean get() = exitCode == 0

		/** The parsed stdout, or null when it is not JSON. */
		fun json(): JsonElement? = runCatching { JsonParser.parseString(stdout) }.getOrNull()

		/** A one-line reason for a failure: the last stderr line, else the exit code. */
		fun failure(): String =
			stderr.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
				?: "adm exited with code $exitCode"
	}

	/** Runs `adm [args]` in [workDir] on the calling thread and waits for it. */
	fun run(project: Project, args: List<String>, workDir: String? = null, timeoutMs: Int = 120_000): Result {
		val adm = ADMExec.resolve(project.basePath)
			?: return Result(-1, "", "adm executable not found; set it in Settings › Languages › ADM")
		val cmd = GeneralCommandLine(adm.toString())
			.withParameters(ADMExec.withBackend(args))
			.withWorkDirectory(workDir ?: project.basePath)
			.withCharset(StandardCharsets.UTF_8)
			.withEnvironment(ADMExec.env())
		val output: ProcessOutput = runCatching {
			CapturingProcessHandler(cmd).runProcess(timeoutMs)
		}.getOrElse { return Result(-1, "", it.message ?: it.toString()) }
		if (output.isTimeout) return Result(-1, output.stdout, "adm ${args.firstOrNull() ?: ""} timed out")
		return Result(output.exitCode, output.stdout, output.stderr)
	}

	/**
	 * Runs the command on a pooled thread and hands the result to [onDone] on
	 * the EDT. For panels that refresh themselves without blocking the UI.
	 */
	fun async(project: Project, args: List<String>, workDir: String? = null, onDone: (Result) -> Unit) {
		ApplicationManager.getApplication().executeOnPooledThread {
			val result = run(project, args, workDir)
			ApplicationManager.getApplication().invokeLater({ onDone(result) }, project.disposed)
		}
	}

	/**
	 * Runs the command under a background progress task titled [title], for
	 * long operations the user started (update, install, publish).
	 */
	fun background(project: Project, title: String, args: List<String>, workDir: String? = null, onDone: (Result) -> Unit) {
		ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, false) {
			private var result: Result? = null
			override fun run(indicator: ProgressIndicator) {
				indicator.isIndeterminate = true
				result = ADMCli.run(project, args, workDir, timeoutMs = 30 * 60_000)
			}
			override fun onFinished() {
				result?.let(onDone)
			}
		})
	}
}
