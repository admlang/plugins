package org.adm.intellij.debugger

import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver.DebuggerLanguage

object ADMDebuggerLanguage : DebuggerLanguage {
	override fun toString(): String = "ADM"
}

