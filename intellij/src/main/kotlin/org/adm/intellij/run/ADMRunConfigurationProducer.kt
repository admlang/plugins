package org.adm.intellij.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement

class ADMRunConfigurationProducer : RunConfigurationProducer<ADMRunConfiguration>(admFactory()) {
	override fun setupConfigurationFromContext(
		configuration: ADMRunConfiguration,
		context: ConfigurationContext,
		sourceElement: Ref<PsiElement>,
	): Boolean {
		val element = sourceElement.get() ?: return false
		val file = element.containingFile ?: return false
		if (file.virtualFile?.extension != "adm") return false

		val marker = ADMRunMarkers.markerAt(file, element.textRange.startOffset) ?: return false

		configuration.name = marker.title
		configuration.args = marker.args
		configuration.workDir = file.virtualFile.parent?.path ?: file.project.basePath
		return true
	}

	override fun isConfigurationFromContext(
		configuration: ADMRunConfiguration,
		context: ConfigurationContext,
	): Boolean {
		val element = context.psiLocation ?: return false
		val file = element.containingFile ?: return false
		if (file.virtualFile?.extension != "adm") return false

		val marker = ADMRunMarkers.markerAt(file, element.textRange.startOffset) ?: return false
		return configuration.args == marker.args && configuration.workDir == (file.virtualFile.parent?.path ?: file.project.basePath)
	}
}

private fun admFactory(): ConfigurationFactory {
	val type = ConfigurationTypeUtil.findConfigurationType(ADMRunConfigurationType::class.java)
	return type.configurationFactories.first()
}
