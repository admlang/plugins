package org.adm.intellij.run

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger

/**
 * Runs an [ADMRunConfiguration] under the Debug executor.
 *
 * Registering a real runner is what makes the debug session part of the
 * platform's execution machinery. Previously the gutter action started an
 * XDebugSession directly against a throwaway anonymous `RunProfile`, with the
 * runner's `execute` left empty -- so the Debug tool window's Rerun button,
 * which re-enters the runner for the session's ExecutionEnvironment, had
 * nothing to call and silently did nothing. Going through a configuration also
 * means the session has an editable, savable configuration behind it, which is
 * where its environment variables and program arguments come from.
 */
class ADMDebugProgramRunner : ProgramRunner<RunnerSettings> {
	override fun getRunnerId(): String = RUNNER_ID

	override fun canRun(executorId: String, profile: RunProfile): Boolean {
		if (executorId != DefaultDebugExecutor.EXECUTOR_ID) return false
		val configuration = profile as? ADMRunConfiguration ?: return false
		// Only `adm run`/`adm test` produce a debuggable binary.
		val subcommand = configuration.args.firstOrNull()?.trim()?.lowercase()
		if (subcommand != "run" && subcommand != "test") return false
		return nativeDebugAvailable()
	}

	override fun execute(environment: ExecutionEnvironment) {
		val configuration = environment.runProfile as? ADMRunConfiguration ?: return
		// The debug bridge builds the binary and touches Swing, so it belongs on
		// the EDT; `execute` may be called from a pooled thread on Rerun.
		ApplicationManager.getApplication().invokeLater {
			try {
				// Hand the environment down: the debug session must belong to it,
				// or Rerun re-executes an environment nothing is attached to.
				ADMDebuggerActions.debugConfiguration(configuration, environment)
			} catch (t: Throwable) {
				log.warn("Failed to start ADM debug session", t)
			}
		}
	}

	private fun nativeDebugAvailable(): Boolean = try {
		Class.forName("org.adm.intellij.debugger.ADMDebuggerBridge")
		true
	} catch (_: Throwable) {
		false
	}

	companion object {
		const val RUNNER_ID: String = "ADMDebugProgramRunner"
		private val log = Logger.getInstance(ADMDebugProgramRunner::class.java)
	}
}
