package org.adm.intellij.ide

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.NlsSafe
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import org.adm.intellij.run.ADMRunConfiguration
import org.adm.intellij.run.ADMRunConfigurationType
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList

/**
 * The right side of the tool window's title bar: `Project: [dropdown]` and
 * the action buttons that apply to the selected unit. Everything runs through
 * an ADM run configuration, so output lands in the Run tool window like any
 * other launch and the Services tab can attach to the process.
 */
object ADMPanelActions {
	fun titleActions(project: Project): List<AnAction> = listOf(
		ProjectSelector(project),
		Separator.getInstance(),
		Run(project),
		Debug(project),
		Build(project),
		Separator.getInstance(),
		PublishGroup(project),
	)

	/** The dropdown: every application, library and plugin of the workspace. */
	private class ProjectSelector(private val project: Project) : ComboBoxAction(), DumbAware {
		override fun getActionUpdateThread() = ActionUpdateThread.BGT

		override fun update(e: AnActionEvent) {
			val ws = ADMWorkspace.getInstance(project)
			val unit = ws.selected
			e.presentation.text = unit?.name ?: if (ws.units.isEmpty()) "No projects" else "Select project"
			e.presentation.icon = unit?.kind?.icon
			e.presentation.description = unit?.let { relative(project, it.file) } ?: "ADM applications, libraries and plugins in this workspace"
		}

		/** Past a handful of units (every testapp counts) the plain menu gives way to a filterable list. */
		override fun createActionPopup(context: DataContext, component: JComponent, disposeCallback: Runnable?): JBPopup {
			val ws = ADMWorkspace.getInstance(project)
			if (ws.units.size <= SEARCHABLE_FROM) return super.createActionPopup(context, component, disposeCallback)
			return searchablePopup(ws, disposeCallback)
		}

		private fun searchablePopup(ws: ADMWorkspace, disposeCallback: Runnable?): JBPopup {
			val model = DefaultListModel<ADMUnit>()
			val list = JBList(model).apply {
				cellRenderer = UnitRenderer(project)
				selectionMode = ListSelectionModel.SINGLE_SELECTION
			}
			val field = SearchTextField(false)
			field.textEditor.emptyText.text = "Search projects"
			fun refill() {
				val q = field.text.trim().lowercase()
				model.clear()
				ws.units.filter { q.isEmpty() || it.name.lowercase().contains(q) || relative(project, it.file).lowercase().contains(q) }.forEach(model::addElement)
				if (model.size > 0) list.selectedIndex = ws.selected?.let { s -> (0 until model.size).firstOrNull { model[it].id == s.id } }?.takeIf { q.isEmpty() } ?: 0
			}
			refill()
			field.textEditor.document.addDocumentListener(object : DocumentListener {
				override fun insertUpdate(e: DocumentEvent) = refill()
				override fun removeUpdate(e: DocumentEvent) = refill()
				override fun changedUpdate(e: DocumentEvent) = refill()
			})
			val content = JPanel(BorderLayout()).apply {
				add(field.apply { border = JBUI.Borders.empty(4, 6) }, BorderLayout.NORTH)
				add(JBScrollPane(list).apply { border = JBUI.Borders.customLineTop(JBUI.CurrentTheme.Popup.separatorColor()) }, BorderLayout.CENTER)
				add(ActionLink("Refresh") { ws.refresh { refill() } }.apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.SOUTH)
				preferredSize = JBUI.size(380, 320)
			}
			val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, field.textEditor)
				.setRequestFocus(true)
				.setFocusable(true)
				.setResizable(true)
				.setCancelOnClickOutside(true)
				.createPopup()
			fun choose() {
				val unit = list.selectedValue ?: return
				popup.closeOk(null)
				ws.select(unit)
			}
			field.textEditor.addKeyListener(object : KeyAdapter() {
				override fun keyPressed(e: KeyEvent) {
					when (e.keyCode) {
						KeyEvent.VK_ENTER -> choose()
						KeyEvent.VK_DOWN -> if (list.selectedIndex < model.size - 1) list.selectedIndex++
						KeyEvent.VK_UP -> if (list.selectedIndex > 0) list.selectedIndex--
						KeyEvent.VK_ESCAPE -> popup.cancel()
						else -> return
					}
					list.ensureIndexIsVisible(list.selectedIndex)
					e.consume()
				}
			})
			object : DoubleClickListener() {
				override fun onDoubleClick(event: MouseEvent): Boolean {
					choose()
					return true
				}
			}.installOn(list)
			popup.addListener(object : JBPopupListener {
				override fun onClosed(event: LightweightWindowEvent) {
					disposeCallback?.run()
				}
			})
			return popup
		}

		override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
			val ws = ADMWorkspace.getInstance(project)
			val group = DefaultActionGroup()
			for (unit in ws.units) {
				group.add(object : DumbAwareAction(unit.name, relative(project, unit.file), unit.kind.icon) {
					override fun actionPerformed(e: AnActionEvent) = ws.select(unit)
				})
			}
			if (ws.units.isNotEmpty()) group.addSeparator()
			group.add(object : DumbAwareAction("Refresh", "List the workspace's units again", AllIcons.Actions.Refresh) {
				override fun actionPerformed(e: AnActionEvent) = ws.refresh()
			})
			return group
		}

		override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
			val button = super.createCustomComponent(presentation, place)
			button.toolTipText = "Project"
			return button
		}
	}

	/** Unit count from which the project dropdown shows a search field instead of a plain menu. */
	private const val SEARCHABLE_FROM = 5

	/** Rows of the dropdown as a list renderer, for popups that show the path column. */
	class UnitRenderer(private val project: Project) : ColoredListCellRenderer<ADMUnit>() {
		override fun customizeCellRenderer(list: JList<out ADMUnit>, value: ADMUnit?, index: Int, selected: Boolean, hasFocus: Boolean) {
			value ?: return
			icon = value.kind.icon
			append(value.name)
			append("  " + relative(project, value.file), SimpleTextAttributes.GRAYED_ATTRIBUTES)
		}
	}

	/** [path] relative to the project root, for the dropdown's second column. */
	fun relative(project: Project, path: String): @NlsSafe String {
		val base = project.basePath ?: return path
		val norm = path.replace('\\', '/')
		val root = base.replace('\\', '/').trimEnd('/') + "/"
		return if (norm.startsWith(root)) norm.removePrefix(root) else path
	}

	/** An action that applies to some unit kinds and launches an `adm` command for the selected one. */
	private abstract class UnitAction(
		protected val project: Project,
		text: String,
		description: String,
		icon: Icon,
		private val kinds: Set<ADMUnit.Kind>,
	) : DumbAwareAction(text, description, icon) {
		override fun getActionUpdateThread() = ActionUpdateThread.BGT

		override fun update(e: AnActionEvent) {
			val unit = ADMWorkspace.getInstance(project).selected
			e.presentation.isEnabledAndVisible = unit != null && unit.kind in kinds
		}

		override fun actionPerformed(e: AnActionEvent) {
			val unit = ADMWorkspace.getInstance(project).selected ?: return
			launch(unit)
		}

		abstract fun launch(unit: ADMUnit)
	}

	private class Run(project: Project) : UnitAction(project, "Run", "Run the selected application", AllIcons.Actions.Execute, setOf(ADMUnit.Kind.Application)) {
		override fun launch(unit: ADMUnit) = execute(project, runSettings(project, unit), debug = false)
	}

	private class Debug(project: Project) : UnitAction(project, "Debug", "Debug the selected application", AllIcons.Actions.StartDebugger, setOf(ADMUnit.Kind.Application)) {
		override fun launch(unit: ADMUnit) = execute(project, runSettings(project, unit), debug = true)
	}

	private class Build(project: Project) : UnitAction(project, "Build", "Build the selected project in release mode; an application with a manifest is packaged, versioned and signed", AllIcons.Actions.Compile, ADMUnit.Kind.entries.toSet()) {
		override fun launch(unit: ADMUnit) {
			val args = when {
				unit.kind == ADMUnit.Kind.Application && unit.manifest != null ->
					listOf("package", "build", "--app", unit.name, "--dir", unit.projectDir, "--sign")
				unit.kind == ADMUnit.Kind.Application ->
					listOf("build", unit.name, "--path", unit.dir, "--release")
				else -> listOf("build", unit.name, "--path", unit.dir)
			}
			execute(project, settings(project, "ADM: build ${unit.name}", args, unit.projectDir), debug = false)
		}
	}

	/** The Publish dropdown: each entry raises one part of the library's version, then builds, signs and publishes. */
	private class PublishGroup(private val project: Project) : DefaultActionGroup("Publish", "Raise the selected library's version, then build, sign and publish it to its registry", AllIcons.Actions.Upload), DumbAware {
		init {
			isPopup = true
			for ((part, label) in listOf("patch" to "Patch", "minor" to "Minor", "major" to "Major")) add(Publish(project, part, label))
		}

		override fun getActionUpdateThread() = ActionUpdateThread.BGT

		override fun update(e: AnActionEvent) {
			e.presentation.isEnabledAndVisible = ADMWorkspace.getInstance(project).selected?.kind == ADMUnit.Kind.Library
		}
	}

	private class Publish(project: Project, private val part: String, label: String) : UnitAction(
		project, "Publish $label", "Raise the $part version in @manifest(version), then build, sign and publish", AllIcons.Actions.Upload, setOf(ADMUnit.Kind.Library),
	) {
		override fun launch(unit: ADMUnit) =
			execute(project, settings(project, "ADM: publish ${unit.name} ($part)", listOf("publish", "--dir", unit.projectDir, "--library", unit.name, "--bump", part), unit.projectDir), debug = false)
	}

	/** The run configuration the gutter icon would make for the application: reused, so its settings stick. */
	private fun runSettings(project: Project, unit: ADMUnit): RunnerAndConfigurationSettings =
		settings(project, "ADM: run ${unit.name}", listOf("run", unit.name, "--path", unit.dir), unit.dir)

	private fun settings(project: Project, name: String, args: List<String>, workDir: String): RunnerAndConfigurationSettings {
		val manager = RunManager.getInstance(project)
		val type = ConfigurationTypeUtil.findConfigurationType(ADMRunConfigurationType::class.java)
		val existing = manager.getConfigurationSettingsList(type).firstOrNull {
			val c = it.configuration as? ADMRunConfiguration
			c != null && c.args == args && c.workDir == workDir
		}
		if (existing != null) return existing
		val created = manager.createConfiguration(name, type.configurationFactories[0])
		(created.configuration as ADMRunConfiguration).apply {
			this.args = args
			this.workDir = workDir
		}
		manager.addConfiguration(created)
		return created
	}

	private fun execute(project: Project, settings: RunnerAndConfigurationSettings, debug: Boolean) {
		RunManager.getInstance(project).selectedConfiguration = settings
		val executor = if (debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance()
		ProgramRunnerUtil.executeConfiguration(settings, executor)
	}
}
