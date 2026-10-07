package org.adm.intellij.ide

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import org.adm.intellij.run.ADMRunConfiguration

/**
 * The "ADM" tool window. The title bar carries the project dropdown and the
 * run/debug/build/test/publish buttons for the selected unit; the tabs are
 * Info, Libraries, Documentation, Tests, Services and Toolchain. Info only shows
 * while a unit is selected and Services only while the selected application
 * is running, so the window never offers a tab with nothing behind it.
 */
class ADMToolWindowFactory : ToolWindowFactory, DumbAware {
	override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
		val disposable = toolWindow.disposable
		val workspace = ADMWorkspace.getInstance(project)
		val factory = ContentFactory.getInstance()
		val manager = toolWindow.contentManager

		val info = factory.createContent(ADMInfoPanel(project, disposable), "Info", false)
		val libraries = factory.createContent(ADMLibrariesPanel(project, disposable), "Libraries", false)
		val docs = factory.createContent(ADMDocumentationPanel(project, disposable), "Documentation", false)
		val tests = factory.createContent(ADMTestsPanel(project, disposable), "Tests", false)
		val servicesPanel = ADMServicesPanel(project, disposable)
		val security = factory.createContent(ADMSecurityPanel(project, disposable, servicesPanel), "Security", false)
		val health = factory.createContent(ADMLintPanel(project, disposable), "Lint", false)
		val services = factory.createContent(servicesPanel, "Services", false)
		val toolchain = factory.createContent(ADMToolchainPanel(project, disposable), "Toolchain", false)
		for (c in listOf(info, libraries, docs, tests, security, health, services, toolchain)) c.isCloseable = false

		// Tabs sit in this order; the two conditional ones are inserted back
		// at their slot so the order never depends on when they appeared.
		val order = listOf(info, libraries, docs, tests, security, health, services, toolchain)
		fun show(content: Content, visible: Boolean) {
			val present = manager.contents.contains(content)
			if (visible && !present) {
				val before = order.indexOf(content)
				val index = manager.contents.count { order.indexOf(it) < before }
				manager.addContent(content, index)
			} else if (!visible && present) {
				manager.removeContent(content, false)
			}
		}

		fun sync() {
			val unit = workspace.selected
			show(info, unit != null)
			show(services, unit != null && unit.kind == ADMUnit.Kind.Application && servicesPanel.isRunning(unit.name))
		}

		show(libraries, true)
		show(docs, true)
		show(tests, true)
		show(security, true)
		show(health, true)
		show(toolchain, true)
		sync()

		(toolWindow as? ToolWindowEx)?.setTitleActions(ADMPanelActions.titleActions(project))
		// The new UI hides a header toolbar until the pointer is over the window;
		// the project dropdown is how the user reads which unit the tabs show.
		toolWindow.component.putClientProperty(ToolWindowContentUi.DONT_HIDE_TOOLBAR_IN_HEADER, true)

		workspace.addListener(object : ADMWorkspace.Listener {
			override fun selectionChanged(unit: ADMUnit?) = sync()
			override fun unitsChanged(units: List<ADMUnit>) = sync()
		}, disposable)
		servicesPanel.onSessionsChanged = { sync() }

		project.messageBus.connect(disposable).subscribe(
			ExecutionManager.EXECUTION_TOPIC,
			object : ExecutionListener {
				override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
					val port = ADMInspect.portOf(handler) ?: ADMInspect.portOf(env) ?: return
					servicesPanel.attach(handler, port, appNameOf(env.runProfile))
				}

				override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
					ADMInspect.forget(handler)
					ADMInspect.forget(env)
					servicesPanel.detach(handler)
				}
			},
		)

		// Debug sessions bypass ExecutionManager (the native bridge starts
		// them via XDebuggerManager.startSessionAndShowTab), so they announce
		// themselves on the debugger topic instead.
		project.messageBus.connect(disposable).subscribe(
			XDebuggerManager.TOPIC,
			object : XDebuggerManagerListener {
				override fun processStarted(debugProcess: XDebugProcess) {
					val port = ADMInspect.takePendingDebugPort() ?: return
					val handler = debugProcess.processHandler
					servicesPanel.attach(handler, port, appNameOf(debugProcess.session.runProfile))
					handler.addProcessListener(object : ProcessListener {
						override fun processTerminated(event: ProcessEvent) {
							servicesPanel.detach(handler)
						}
					})
				}
			},
		)

		workspace.refresh()
	}

	/** The application an `adm run NAME …` configuration launches, else null. */
	private fun appNameOf(profile: Any?): String? {
		val args = (profile as? ADMRunConfiguration)?.args ?: return null
		return if (args.firstOrNull() == "run") args.getOrNull(1)?.takeIf { !it.startsWith("--") } else null
	}
}
