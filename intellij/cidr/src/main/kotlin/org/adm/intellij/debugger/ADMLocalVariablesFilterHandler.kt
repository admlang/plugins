package org.adm.intellij.debugger

import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.XSourcePosition
import com.jetbrains.cidr.execution.debugger.backend.LLValue
import com.jetbrains.cidr.execution.debugger.evaluation.LocalVariablesFilterHandler
import java.util.concurrent.CompletableFuture

class ADMLocalVariablesFilterHandler : LocalVariablesFilterHandler {
	private val log = Logger.getInstance(ADMLocalVariablesFilterHandler::class.java)

	override fun filterVars(proj: Project, pos: XSourcePosition, vars: List<LLValue>): CompletableFuture<List<LLValue>> {
		// Filter compiler-generated temporaries regardless of whether the current frame is in `.adm` or generated `.c`.
		//
		// Nothing here may throw. This future feeds the variables pane, so an
		// exception escaping the loop leaves it uncompleted and the pane renders
		// empty -- which looks like "the debugger shows no variables" rather than
		// like a plugin error. A variable we cannot map is shown unmapped, and a
		// failure of the whole pass shows the unfiltered list.
		return try {
			val filtered = ArrayList<LLValue>(vars.size)
			for (v in vars) {
				val name = try {
					v.name
				} catch (t: Throwable) {
					log.warn("Failed to read a local's name", t)
					null
				} ?: continue
				if (isInternalDebugVar(name)) continue
				filtered.add(
					try {
						remapTypeForDisplay(v)
					} catch (t: Throwable) {
						log.warn("Failed to map type for local '$name'", t)
						v
					}
				)
			}
			CompletableFuture.completedFuture(filtered)
		} catch (t: Throwable) {
			log.warn("Local variable filtering failed; showing the unfiltered list", t)
			CompletableFuture.completedFuture(vars)
		}
	}

	override fun canFilterAtPos(proj: Project, pos: XSourcePosition): Boolean {
		// Always allow filtering; the handler itself only strips known compiler temporaries.
		return true
	}

	private fun remapTypeForDisplay(v: LLValue): LLValue {
		val mapped = mapCTypeToADM(v.displayType ?: v.type ?: "")
		if (mapped.isBlank()) return v

		return try {
			LLValue(
				v.name,
				mapped,
				mapped,
				v.address,
				v.typeClass,
				v.referenceExpression,
				v.fullExpression,
			)
		} catch (_: Throwable) {
			v
		}
	}

	private fun mapCTypeToADM(raw: String): String {
		var t = raw.trim()
		if (t.isEmpty()) return ""

		// Debugger sometimes wraps types like `{ADMArray *}`. Guard the length:
		// a lone "{" satisfies both startsWith and endsWith.
		if (t.length >= 2 && t.startsWith("{") && t.endsWith("}")) {
			t = t.substring(1, t.length - 1).trim()
		}
		t = t.replace(" *", "*").replace("* ", "*")

		// Primitive integer aliases.
		return when (t) {
			"int64_t" -> "int"
			"uint64_t" -> "uint"
			"int32_t" -> "int32"
			"uint32_t" -> "uint32"
			"int16_t" -> "int16"
			"uint16_t" -> "uint16"
			"int8_t" -> "int8"
			"uint8_t" -> "uint8"
			"bool" -> "bool"
			"float" -> "float32"
			"double" -> "float64"
			else -> {
				// Runtime-backed ADM types.
				when {
					t.contains("ADMString") -> "string"
					t.contains("ADMArray") -> "T[]"
					t.contains("ADMMap") -> "map<?, ?>"
					t.contains("ADMIface") -> "any"
					t.contains("ADMError") -> "error"
					else -> ""
				}
			}
		}
	}

	private fun isInternalDebugVar(name: String): Boolean {
		if (name.isEmpty()) return true

		// Common compiler-generated locals we don't want to show in the ADM view.
		// `tmp` is deliberately NOT filtered: it is a legitimate user variable
		// name, and filtering the bare prefix hid every user `tmp*` local.
		if (name.startsWith("__adm")) return true
		if (name.startsWith("adm_")) return true
		if (name.startsWith("__tmp")) return true

		// HIR/C backend temporaries are typically v0, v1, v2...
		// Some debuggers render these as `v0`, `v0$1`, `v0_3`, etc.
		if (name.length >= 2 && name[0] == 'v' && name[1] in '0'..'9') {
			var i = 1
			while (i < name.length && name[i] in '0'..'9') i++
			if (i == name.length) return true
			return when (name[i]) {
				'$', '_' -> true
				else -> false
			}
		}

		return false
	}
}
