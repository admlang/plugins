package org.adm.intellij.debugger

import com.intellij.execution.configurations.RunProfile
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.jetbrains.cidr.execution.debugger.CidrDebuggerLanguageSupport
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver

class ADMDebuggerLanguageSupport : CidrDebuggerLanguageSupport() {
	override fun getSupportedDebuggerLanguages(): Set<DebuggerDriver.DebuggerLanguage> {
		return setOf(ADMDebuggerLanguage)
	}

	override fun createEditor(profile: RunProfile?): XDebuggerEditorsProvider? {
		return createEditorProvider()
	}
}

