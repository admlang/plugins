package org.adm.intellij.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.configuration.EnvironmentVariablesTextFieldWithBrowseButton
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.util.execution.ParametersListUtil
import org.jdom.Element
import java.awt.Dimension
import java.nio.charset.StandardCharsets
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class ADMRunConfiguration(
	project: com.intellij.openapi.project.Project,
	factory: com.intellij.execution.configurations.ConfigurationFactory,
	name: String,
) : RunConfigurationBase<Any?>(project, factory, name) {
	var workDir: String? = null
	// Args passed to the `adm` CLI (e.g. `run MyApp --path ...`).
	var args: List<String> = emptyList()
	// Args forwarded to the application (after `--`).
	var appArgs: List<String> = emptyList()
	// User-supplied environment variables, and whether the IDE's own environment
	// is inherited. Both the Run and the Debug path read these through
	// [effectiveEnv], so a variable set here reaches the debugged program too.
	var envVars: Map<String, String> = emptyMap()
	var passParentEnv: Boolean = true

	/**
     * Environment for the launched process: the ADM tool variables
     * (ADM_HOME/ADM_CACHE/ADM_LIB/...) with the user's own entries layered on
     * top, so a configuration can override any of them.
     */
	fun effectiveEnv(): Map<String, String> {
		val env = LinkedHashMap<String, String>(ADMExec.env())
		env.putAll(envVars)
		return env
	}

	override fun getConfigurationEditor(): SettingsEditor<out RunConfigurationBase<Any?>> {
		return Editor()
	}

	override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
		val workDir = this.workDir ?: project.basePath
		if (workDir.isNullOrBlank()) {
			Messages.showErrorDialog(project, "Cannot determine working directory for ADM run.", "ADM")
			return null
		}

		val adm = ADMExec.resolve(project.basePath)
		if (adm == null) {
			Messages.showErrorDialog(project, "Could not locate the `adm` executable.", "ADM")
			return null
		}

		val runArgs = args
		if (runArgs.isEmpty()) {
			Messages.showErrorDialog(project, "ADM run configuration has no arguments.", "ADM")
			return null
		}

		return object : CommandLineState(environment) {
			override fun startProcess(): ProcessHandler {
				val fullArgs = ArrayList<String>(runArgs.size + 3 + appArgs.size)
				fullArgs.addAll(ADMExec.withBackend(runArgs))
				if (appArgs.isNotEmpty()) {
					fullArgs.add("--")
					fullArgs.addAll(appArgs)
				}
				// effectiveEnv, not ADMExec.env: the user's own Environment
				// entries from the editor apply to plain Run too, not only to
				// the Debug path.
				val env = LinkedHashMap(effectiveEnv())
				val idePort = org.adm.intellij.ide.ADMInspect.pickFreePort()
				env["ADM_IDE_PORT"] = idePort.toString()

				val cmd = GeneralCommandLine(adm.toString())
					.withWorkDirectory(workDir)
					.withCharset(StandardCharsets.UTF_8)
					.withEnvironment(env)
					.withParameters(fullArgs)

				return try {
					val handler = KillableProcessHandler(cmd)
					org.adm.intellij.ide.ADMInspect.register(handler, idePort)
					handler
				} catch (t: Throwable) {
					log.warn("Failed to start adm process", t)
					throw t
				}
			}
		}
	}

	// Args are child elements, one per value: a NUL-joined attribute is not
	// legal XML, and JDOM throws on it the moment a configuration has two
	// arguments — which silently disabled Apply/OK/Run in the settings dialog
	// (the platform serializes a snapshot to detect modification).
	override fun writeExternal(element: Element) {
		super.writeExternal(element)
		element.setAttribute("admWorkDir", workDir.orEmpty())
		writeArgList(element, "admArg", args)
		writeArgList(element, "admAppArg", appArgs)
		EnvironmentVariablesComponent.writeExternal(element, envVars)
		element.setAttribute("admPassParentEnv", passParentEnv.toString())
	}

	override fun readExternal(element: Element) {
		super.readExternal(element)
		workDir = element.getAttributeValue("admWorkDir").takeIf { !it.isNullOrBlank() }
		args = readArgList(element, "admArg", "admArgs")
		appArgs = readArgList(element, "admAppArg", "admAppArgs")
		val loaded = LinkedHashMap<String, String>()
		EnvironmentVariablesComponent.readExternal(element, loaded)
		envVars = loaded
		passParentEnv = element.getAttributeValue("admPassParentEnv")?.toBooleanStrictOrNull() ?: true
	}

	private fun writeArgList(element: Element, name: String, values: List<String>) {
		for (v in values) {
			val child = Element(name)
			child.setAttribute("value", v)
			element.addContent(child)
		}
	}

	private fun readArgList(element: Element, name: String, legacyAttr: String): List<String> {
		val children = element.getChildren(name)
		if (children.isNotEmpty()) {
			return children.mapNotNull { it.getAttributeValue("value") }
		}
		// Configurations saved by older plugin builds used a NUL-joined
		// attribute; only single-argument values ever persisted successfully,
		// so what can exist on disk has no separator in it.
		val raw = element.getAttributeValue(legacyAttr).orEmpty()
		return if (raw.isBlank()) emptyList() else listOf(raw)
	}

	companion object {
		private val log = Logger.getInstance(ADMRunConfiguration::class.java)
	}

	private class Editor : SettingsEditor<ADMRunConfiguration>() {
		private val workDirField = TextFieldWithBrowseButton()
		private val argsField = RawCommandLineEditor()
		private val appArgsField = RawCommandLineEditor()
		private val envField = EnvironmentVariablesTextFieldWithBrowseButton()

		override fun createEditor(): JComponent {
			workDirField.addBrowseFolderListener(
				"Working Directory",
				"Directory used as the working directory when running `adm`.",
				null,
				com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor(),
			)
			argsField.minimumSize = Dimension(400, argsField.minimumSize.height)
			appArgsField.minimumSize = Dimension(400, appArgsField.minimumSize.height)

			val panel = JPanel()
			panel.layout = java.awt.GridBagLayout()

			val c = java.awt.GridBagConstraints()
			c.gridx = 0
			c.gridy = 0
			c.anchor = java.awt.GridBagConstraints.WEST
			c.insets = java.awt.Insets(0, 0, 6, 8)
			panel.add(JLabel("Work dir:"), c)

			c.gridx = 1
			c.weightx = 1.0
			c.fill = java.awt.GridBagConstraints.HORIZONTAL
			panel.add(workDirField, c)

			c.gridx = 0
			c.gridy = 1
			c.weightx = 0.0
			c.fill = java.awt.GridBagConstraints.NONE
			c.insets = java.awt.Insets(0, 0, 6, 8)
			panel.add(JLabel("Arguments:"), c)

			c.gridx = 1
			c.weightx = 1.0
			c.fill = java.awt.GridBagConstraints.HORIZONTAL
			panel.add(argsField, c)

			c.gridx = 0
			c.gridy = 2
			c.weightx = 0.0
			c.fill = java.awt.GridBagConstraints.NONE
			c.insets = java.awt.Insets(0, 0, 6, 8)
			panel.add(JLabel("App args:"), c)

			c.gridx = 1
			c.weightx = 1.0
			c.fill = java.awt.GridBagConstraints.HORIZONTAL
			panel.add(appArgsField, c)

			c.gridx = 0
			c.gridy = 3
			c.weightx = 0.0
			c.fill = java.awt.GridBagConstraints.NONE
			c.insets = java.awt.Insets(0, 0, 6, 8)
			panel.add(JLabel("Environment:"), c)

			c.gridx = 1
			c.weightx = 1.0
			c.fill = java.awt.GridBagConstraints.HORIZONTAL
			panel.add(envField, c)

			return panel
		}

		override fun resetEditorFrom(s: ADMRunConfiguration) {
			workDirField.text = s.workDir.orEmpty()
			argsField.text = ParametersListUtil.join(s.args)
			appArgsField.text = ParametersListUtil.join(s.appArgs)
			envField.envs = s.envVars
			envField.isPassParentEnvs = s.passParentEnv
		}

		override fun applyEditorTo(s: ADMRunConfiguration) {
			val wd = workDirField.text.trim()
			s.workDir = wd.ifBlank { null }

			val raw = argsField.text.trim()
			s.args = if (raw.isBlank()) emptyList() else ParametersListUtil.parse(raw, false, true)

			val rawApp = appArgsField.text.trim()
			s.appArgs = if (rawApp.isBlank()) emptyList() else ParametersListUtil.parse(rawApp, false, true)

			s.envVars = LinkedHashMap(envField.envs)
			s.passParentEnv = envField.isPassParentEnvs
		}
	}
}
