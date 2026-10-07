package org.adm.intellij.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import org.adm.intellij.ADMIcons

class ADMRunConfigurationType : ConfigurationType {
	private val factory = ADMRunConfigurationFactory(this)

	override fun getDisplayName(): String = "ADM"
	override fun getConfigurationTypeDescription(): String = "Run ADM applications/tests via the `adm` CLI."
	override fun getIcon() = ADMIcons.FILE
	override fun getId(): String = "ADM_RUN_CONFIGURATION"
	override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(factory)

	private class ADMRunConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
		override fun getId(): String = "ADM_RUN_CONFIGURATION_FACTORY"

		override fun createTemplateConfiguration(project: Project): RunConfiguration {
			return ADMRunConfiguration(project, this, "ADM")
		}
	}
}
