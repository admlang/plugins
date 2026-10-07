package org.adm.intellij.run

import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.util.Function

class ADMRunLineMarkerProvider : RunLineMarkerContributor() {
	override fun getInfo(element: PsiElement): Info? {
		val file = element.containingFile ?: return null
		if (file.virtualFile?.extension != "adm") return null
		if (element is PsiWhiteSpace) return null

		val marker = ADMRunMarkers.markerAt(file, element.textRange.startOffset) ?: return null

		val icon = when (marker.kind) {
			ADMRunMarkers.Kind.Test, ADMRunMarkers.Kind.Benchmark, ADMRunMarkers.Kind.Suite -> AllIcons.RunConfigurations.TestState.Run
			ADMRunMarkers.Kind.Application -> AllIcons.Actions.Execute
			ADMRunMarkers.Kind.Plugin, ADMRunMarkers.Kind.Library -> AllIcons.Actions.Compile
		}
		val tooltip = when (marker.kind) {
			ADMRunMarkers.Kind.Test -> "Run test"
			ADMRunMarkers.Kind.Benchmark -> "Run benchmark"
			ADMRunMarkers.Kind.Suite -> "Run suite"
			ADMRunMarkers.Kind.Application -> "Run application"
			ADMRunMarkers.Kind.Plugin -> "Build plugin (.admplugin)"
			ADMRunMarkers.Kind.Library -> "Build library (.admlib)"
		}

		// The platform's own Run/Debug actions, which route through
		// ADMRunConfigurationProducer -> ADMRunConfiguration -> the registered
		// runners. A hand-rolled "Debug" entry used to be appended here; it
		// started an XDebugSession against a throwaway RunProfile, so the
		// resulting session had no configuration behind it -- nothing to edit,
		// and a Rerun button with nothing to re-enter. ADMDebugProgramRunner
		// serves the Debug executor now, so the standard action covers it.
		val actions = ExecutorAction.getActions(0)

		return Info(icon, actions, Function { tooltip })
	}
}
