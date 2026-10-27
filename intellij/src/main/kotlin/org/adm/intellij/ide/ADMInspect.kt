package org.adm.intellij.ide

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a launched ADM process serves its IDE channel (std.ide): the port on
 * 127.0.0.1 and the secret a connection opens with (`hello <token>`). Both go
 * to the process as ADM_IDE_PORT and ADM_IDE_TOKEN, which it takes out of its
 * environment as it starts; another process on the machine cannot use the
 * channel without the token.
 */
data class ADMEndpoint(val port: Int, val token: String) {
	/** Adds the two variables the process reads to [env]. */
	fun exportTo(env: MutableMap<String, String>) {
		env["ADM_IDE_PORT"] = port.toString()
		env["ADM_IDE_TOKEN"] = token
	}
}

/**
 * Endpoints the run integration assigned to launched ADM processes. The run
 * configuration exports an endpoint to the process (std.ide's IDE service
 * listens only when both variables are present), registers the handler
 * here, and the ADM tool window looks the endpoint up when the platform
 * announces the process start.
 */
object ADMInspect {
	private val endpoints = ConcurrentHashMap<ProcessHandler, ADMEndpoint>()

	// Debug sessions: the process handler is created deep inside the native
	// debug bridge, so the endpoint is keyed on the ExecutionEnvironment the
	// session belongs to instead.
	private val envEndpoints = ConcurrentHashMap<ExecutionEnvironment, ADMEndpoint>()

	private val random = SecureRandom()

	/** A free port and a fresh 32-byte token, for one run. */
	fun newEndpoint(): ADMEndpoint {
		val port = ServerSocket(0).use { it.localPort }
		val bytes = ByteArray(32)
		random.nextBytes(bytes)
		return ADMEndpoint(port, bytes.joinToString("") { "%02x".format(it) })
	}

	fun register(handler: ProcessHandler, endpoint: ADMEndpoint) {
		endpoints[handler] = endpoint
	}

	fun register(environment: ExecutionEnvironment, endpoint: ADMEndpoint) {
		envEndpoints[environment] = endpoint
	}

	fun endpointOf(handler: ProcessHandler): ADMEndpoint? = endpoints[handler]

	fun endpointOf(environment: ExecutionEnvironment): ADMEndpoint? = envEndpoints[environment]

	fun forget(handler: ProcessHandler) {
		endpoints.remove(handler)
	}

	fun forget(environment: ExecutionEnvironment) {
		envEndpoints.remove(environment)
	}

	// Debug sessions start through XDebuggerManager.startSessionAndShowTab,
	// which bypasses ExecutionManager entirely — EXECUTION_TOPIC never fires
	// and no ExecutionEnvironment reaches the tool window. The endpoint is
	// handed over through a single pending slot instead, consumed by the
	// debugger topic listener when the next debug process starts. One debug
	// session at a time; a concurrent non-ADM debug start between the two
	// calls would consume it harmlessly (the ADM session then shows no panel).
	@Volatile private var pendingDebugEndpoint: ADMEndpoint? = null

	fun setPendingDebugEndpoint(endpoint: ADMEndpoint) {
		pendingDebugEndpoint = endpoint
	}

	fun takePendingDebugEndpoint(): ADMEndpoint? {
		val e = pendingDebugEndpoint
		pendingDebugEndpoint = null
		return e
	}
}
