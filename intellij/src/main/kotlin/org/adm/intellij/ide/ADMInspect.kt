package org.adm.intellij.ide

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Ports the run integration assigned to launched ADM processes. The run
 * configuration exports ADM_IDE_PORT to the process (std.ide's IDE service
 * binds 127.0.0.1:port only when the variable is present), registers the
 * handler here, and the ADM tool window looks the port up when the platform
 * announces the process start.
 */
object ADMInspect {
	private val ports = ConcurrentHashMap<ProcessHandler, Int>()

	// Debug sessions: the process handler is created deep inside the native
	// debug bridge, so the port is keyed on the ExecutionEnvironment the
	// session belongs to instead.
	private val envPorts = ConcurrentHashMap<ExecutionEnvironment, Int>()

	fun pickFreePort(): Int = ServerSocket(0).use { it.localPort }

	fun register(handler: ProcessHandler, port: Int) {
		ports[handler] = port
	}

	fun register(environment: ExecutionEnvironment, port: Int) {
		envPorts[environment] = port
	}

	fun portOf(handler: ProcessHandler): Int? = ports[handler]

	fun portOf(environment: ExecutionEnvironment): Int? = envPorts[environment]

	fun forget(handler: ProcessHandler) {
		ports.remove(handler)
	}

	fun forget(environment: ExecutionEnvironment) {
		envPorts.remove(environment)
	}

	// Debug sessions start through XDebuggerManager.startSessionAndShowTab,
	// which bypasses ExecutionManager entirely — EXECUTION_TOPIC never fires
	// and no ExecutionEnvironment reaches the tool window. The port is handed
	// over through a single pending slot instead, consumed by the debugger
	// topic listener when the next debug process starts. One debug session at
	// a time; a concurrent non-ADM debug start between the two calls would
	// consume it harmlessly (the ADM session then shows no panel).
	@Volatile private var pendingDebugPort: Int? = null

	fun setPendingDebugPort(port: Int) {
		pendingDebugPort = port
	}

	fun takePendingDebugPort(): Int? {
		val p = pendingDebugPort
		pendingDebugPort = null
		return p
	}
}
