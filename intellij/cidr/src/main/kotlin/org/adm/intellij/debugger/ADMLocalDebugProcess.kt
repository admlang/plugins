package org.adm.intellij.debugger

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.TextConsoleBuilder
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.XDebugSession
import com.jetbrains.cidr.execution.RunParameters
import com.jetbrains.cidr.execution.debugger.CidrLocalDebugProcess
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriver

class ADMLocalDebugProcess(parameters: RunParameters, session: XDebugSession, consoleBuilder: TextConsoleBuilder) :
	CidrLocalDebugProcess(parameters, session, consoleBuilder, { Filter.EMPTY_ARRAY }, true) {

	/**
	 * Registers the ADM LLDB formatters before the target is loaded, so the
	 * Variables view shows array elements instead of the raw `{data, len, cap}`
	 * handle. This runs on the debugger's command thread with a live driver, which
	 * is the earliest point a console command is guaranteed to be accepted.
	 */
	override fun doLoadTarget(driver: DebuggerDriver): DebuggerDriver.Inferior {
		if (driver is LLDBDriver) {
			// Report the outcome in the debug console: whether array elements show up
			// depends entirely on this import, so silence here is hard to diagnose.
			val command = ADMLldbFormatters.importCommand()
			if (command == null) {
				report(
					"ADM: LLDB formatters not found (looked for ~/.adm/lldb/adm_formatters.py); " +
						"arrays will show their raw handle. Run build.sh or set ADM_LLDB_FORMATTERS.",
					ConsoleViewContentType.SYSTEM_OUTPUT,
				)
			} else {
				try {
					driver.executeConsoleCommand(command)
					report("ADM: $command", ConsoleViewContentType.SYSTEM_OUTPUT)
				} catch (t: Throwable) {
					log.warn("Failed to load ADM LLDB formatters", t)
					report(
						"ADM: failed to load LLDB formatters: ${t.message ?: t.javaClass.simpleName}",
						ConsoleViewContentType.ERROR_OUTPUT,
					)
				}
			}
		}
		return super.doLoadTarget(driver)
	}

	private fun report(message: String, type: ConsoleViewContentType) {
		try {
			getConsole().print(message + "\n", type)
		} catch (t: Throwable) {
			log.warn(message, t)
		}
	}

	private companion object {
		private val log = Logger.getInstance(ADMLocalDebugProcess::class.java)
	}
}
