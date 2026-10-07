package org.adm.intellij.ide

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import org.adm.intellij.ADMIcons
import org.adm.intellij.run.ADMExec
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File
import java.nio.charset.StandardCharsets
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The Tests tab: the selected project's `check` suites as a tree of module,
 * suite and test on the left, the selected node's output on the right, fed
 * by `adm test --json`. Rows carry their state (spinner, pass, fail, mixed),
 * their duration summed up the tree, and per module the line coverage the
 * run reported. Auto-run re-tests a module after its files change, debounced
 * and queued behind a run in progress.
 */
class ADMTestsPanel(private val project: Project, parentDisposable: Disposable) : JBPanelWithEmptyText(BorderLayout()), Disposable {
	enum class Status { None, Running, Passed, Failed, Mixed, Skipped }

	/** One test as `adm test --list --json` reports it, plus what the last run said. */
	private class Case(val name: String, val module: String, val suites: List<String>, val leaf: String, val file: String, val line: Int) {
		var status = Status.None
		var seconds = 0.0
		var skipReason: String? = null
		val output = ArrayList<Pair<String, ConsoleViewContentType>>()
		fun reset() {
			status = Status.None
			seconds = 0.0
			skipReason = null
			output.clear()
		}
	}

	private class Module(val name: String) {
		var coverage: Double? = null
	}

	private class Suite(val module: String, val path: List<String>) {
		val label: String get() = path.last()
	}

	// --- model ---------------------------------------------------------------------

	private val root = DefaultMutableTreeNode()
	private val model = DefaultTreeModel(root)
	private val tree = Tree(model)
	private val cases = LinkedHashMap<String, Case>()
	private val moduleNodes = LinkedHashMap<String, DefaultMutableTreeNode>()
	/** Directory of a module's files -> module, for mapping an edited file to what to rerun. */
	private val moduleOfDir = HashMap<String, String>()
	private val moduleFiles = HashMap<String, LinkedHashSet<String>>()
	/** Lines that belong to no test: build progress, diagnostics, stderr. */
	private val rootOutput = ArrayList<Pair<String, ConsoleViewContentType>>()

	private var scopeDir: String? = null
	private var listedFor: String? = null
	private var listing = false
	private var handler: OSProcessHandler? = null
	private var stopping = false
	private var summary: IntArray? = null
	private var lastRunFailed = false
	private var statusText = ""
	private val pendingCoverage = HashMap<String, ADMTestCoverage.Lines>()

	// --- auto-run ------------------------------------------------------------------

	private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
	private val dirtyModules = LinkedHashSet<String>()
	private var dirtyAll = false
	private var queued = false

	// --- ui ------------------------------------------------------------------------

	private val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
	private val statusLabel = JBLabel("", SwingConstants.LEFT).apply { border = JBUI.Borders.empty(4, 8) }
	private val bar = object : JPanel() {
		override fun paintComponent(g: Graphics) {
			val color = when {
				handler != null -> JBColor.namedColor("ProgressBar.progressColor", JBColor(0x4083C9, 0x4083C9))
				lastRunFailed -> FAILED_COLOR
				summary != null -> PASSED_COLOR
				else -> return
			}
			g.color = color
			g.fillRect(0, 0, width, height)
		}
	}.apply { preferredSize = Dimension(0, JBUI.scale(3)); isOpaque = false }
	private val splitter = OnePixelSplitter(false, 0.35f).apply { splitterProportionKey = "ADM.Tests.split" }
	private val spinner = Timer(150) { if (handler != null) tree.repaint() }
	private var showPassed: Boolean
		get() = PropertiesComponent.getInstance(project).getBoolean("ADM.Tests.showPassed", true)
		set(value) = PropertiesComponent.getInstance(project).setValue("ADM.Tests.showPassed", value, true)
	private var autoRun: Boolean
		get() = PropertiesComponent.getInstance(project).getBoolean("ADM.Tests.autoRun", false)
		set(value) = PropertiesComponent.getInstance(project).setValue("ADM.Tests.autoRun", value, false)
	private var withCoverage: Boolean
		get() = PropertiesComponent.getInstance(project).getBoolean("ADM.Tests.coverage", true)
		set(value) = PropertiesComponent.getInstance(project).setValue("ADM.Tests.coverage", value, true)

	init {
		Disposer.register(parentDisposable, this)
		Disposer.register(this, console)
		tree.isRootVisible = false
		tree.showsRootHandles = true
		tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
		tree.cellRenderer = Renderer()
		tree.emptyText.text = "No tests"
		tree.addTreeSelectionListener { showSelected() }
		object : DoubleClickListener() {
			override fun onDoubleClick(event: MouseEvent): Boolean = openSelected()
		}.installOn(tree)
		tree.addKeyListener(object : KeyAdapter() {
			override fun keyPressed(e: KeyEvent) {
				if (e.keyCode == KeyEvent.VK_ENTER && openSelected()) e.consume()
			}
		})

		val run = object : DumbAwareAction("Run Tests", "Run every test of the selected project; running tests are stopped first", AllIcons.Actions.Execute) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.icon = if (summary != null || handler != null) AllIcons.Actions.Rerun else AllIcons.Actions.Execute
				e.presentation.isEnabled = scopeDir != null
			}
			override fun actionPerformed(e: AnActionEvent) = runAll()
		}
		val rerunFailed = object : DumbAwareAction("Rerun Failed Tests", "Run only the tests that failed last time", AllIcons.RunConfigurations.RerunFailedTests) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.isEnabled = handler == null && cases.values.any { it.status == Status.Failed }
			}
			override fun actionPerformed(e: AnActionEvent) {
				val failed = cases.values.filter { it.status == Status.Failed }.map { it.name }
				if (failed.isNotEmpty()) start(failed.flatMap { listOf("--run", "=$it") }, failed.toSet())
			}
		}
		val auto = object : ToggleAction("Rerun Automatically", "Rerun a module's tests after its files change, once typing pauses and the current run has finished", AllIcons.Actions.RerunAutomatically), DumbAware {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun isSelected(e: AnActionEvent) = autoRun
			override fun setSelected(e: AnActionEvent, state: Boolean) {
				autoRun = state
				if (!state) {
					alarm.cancelAllRequests()
					dirtyModules.clear()
					dirtyAll = false
					queued = false
				}
			}
		}
		val stop = object : DumbAwareAction("Stop", "Stop the running tests", AllIcons.Actions.Suspend) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.isEnabled = handler != null
			}
			override fun actionPerformed(e: AnActionEvent) = stop()
		}
		val passed = object : ToggleAction("Show Passed", "Show tests that passed", AllIcons.RunConfigurations.ShowPassed), DumbAware {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun isSelected(e: AnActionEvent) = showPassed
			override fun setSelected(e: AnActionEvent, state: Boolean) {
				showPassed = state
				rebuildTree()
			}
		}
		val coverage = object : ToggleAction("Collect Coverage", "Measure line coverage and show it in the editor gutter", AllIcons.Toolwindows.ToolWindowCoverage), DumbAware {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				super.update(e)
				if (!COVERAGE_SUPPORTED) {
					e.presentation.isEnabled = false
					e.presentation.description = "Coverage is not available in this compiler"
				}
			}
			override fun isSelected(e: AnActionEvent) = COVERAGE_SUPPORTED && withCoverage
			override fun setSelected(e: AnActionEvent, state: Boolean) {
				withCoverage = state
				ADMTestCoverage.getInstance(project).visible = state
			}
		}
		val expand = object : DumbAwareAction("Expand All", null, AllIcons.Actions.Expandall) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
		}
		val collapse = object : DumbAwareAction("Collapse All", null, AllIcons.Actions.Collapseall) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun actionPerformed(e: AnActionEvent) = TreeUtil.collapseAll(tree, 1)
		}
		val refresh = object : DumbAwareAction("Refresh", "Discover the tests again", AllIcons.Actions.Refresh) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.icon = if (listing) AnimatedIcon.Default.INSTANCE else AllIcons.Actions.Refresh
				e.presentation.isEnabled = !listing && scopeDir != null
			}
			override fun actionPerformed(e: AnActionEvent) = list(force = true)
		}
		val group = DefaultActionGroup(run, rerunFailed, auto, stop, Separator.getInstance(), passed, coverage, Separator.getInstance(), expand, collapse, refresh)
		val toolbar = ActionManager.getInstance().createActionToolbar("ADMTests", group, true)
		toolbar.targetComponent = this

		val left = JPanel(BorderLayout()).apply {
			add(toolbar.component, BorderLayout.NORTH)
			add(JBScrollPane(tree).apply { border = JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor()) }, BorderLayout.CENTER)
		}
		val right = JPanel(BorderLayout()).apply {
			add(JPanel(BorderLayout()).apply {
				add(statusLabel, BorderLayout.CENTER)
				add(bar, BorderLayout.SOUTH)
			}, BorderLayout.NORTH)
			add(console.component, BorderLayout.CENTER)
		}
		splitter.firstComponent = left
		splitter.secondComponent = right
		add(splitter, BorderLayout.CENTER)
		emptyText.text = "Select a project to see its tests"

		ADMWorkspace.getInstance(project).addListener(object : ADMWorkspace.Listener {
			override fun selectionChanged(unit: ADMUnit?) = scopeTo(unit)
			override fun unitsChanged(units: List<ADMUnit>) {
				if (scopeDir == null) scopeTo(ADMWorkspace.getInstance(project).selected)
			}
		}, this)
		EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
			override fun documentChanged(event: DocumentEvent) = changed(event)
		}, this)
		scopeTo(ADMWorkspace.getInstance(project).selected)
	}

	// --- scope and discovery -----------------------------------------------------

	/** The tests shown are those under the selected unit's project, else the workspace root. */
	private fun scopeTo(unit: ADMUnit?) {
		val dir = unit?.projectDir ?: project.basePath
		if (dir == scopeDir && listedFor == dir) return
		if (handler != null) stop()
		scopeDir = dir
		list(force = false)
	}

	private fun list(force: Boolean) {
		val dir = scopeDir ?: return
		if (!force && listedFor == dir) return
		if (listing) return
		listing = true
		ADMCli.async(project, listOf("test", ".", "--list", "--json"), dir) { r ->
			listing = false
			if (scopeDir != dir) return@async
			listedFor = dir
			cases.clear()
			moduleOfDir.clear()
			moduleFiles.clear()
			rootOutput.clear()
			summary = null
			lastRunFailed = false
			val rows = if (r.ok) r.json()?.asArrayOrNull().objects() else emptyList()
			for (o in rows) {
				val c = caseOf(o.str("name") ?: continue, o.str("module") ?: "", o.str("file") ?: "", o.int("line") ?: 0)
				if (o.bool("skip")) c.status = Status.Skipped
				cases[c.name] = c
			}
			if (!r.ok) rootOutput.add(r.failure() + "\n" to ConsoleViewContentType.ERROR_OUTPUT)
			statusText = if (cases.isEmpty()) (if (r.ok) "No tests" else "Could not list tests") else "${cases.size} tests"
			rebuildTree()
			showSelected()
		}
	}

	/** Splits `module::check("Suite")::test` into its parts. */
	private fun caseOf(name: String, module: String, file: String, line: Int): Case {
		val parts = name.split("::")
		val mod = module.ifEmpty { parts.first() }
		val suites = parts.drop(1).dropLast(1).map { seg ->
			val inner = seg.substringAfter('(', "").substringBeforeLast(')')
			(if (inner.isEmpty()) seg else inner).trim('"')
		}
		val c = Case(name, mod, suites, parts.last(), file, line)
		if (file.isNotEmpty()) {
			val abs = resolve(file)
			moduleOfDir[File(abs).parent ?: ""] = mod
			moduleFiles.getOrPut(mod) { LinkedHashSet() }.add(file)
		}
		return c
	}

	private fun resolve(path: String): String {
		val f = File(path)
		return if (f.isAbsolute) f.absolutePath else File(scopeDir ?: project.basePath ?: ".", path).absolutePath
	}

	// --- tree -----------------------------------------------------------------------

	private fun rebuildTree() {
		val selected = selectedCase()?.name
		val expanded = HashSet<String>()
		for (row in 0 until tree.rowCount) if (tree.isExpanded(row)) keyOf(tree.getPathForRow(row))?.let(expanded::add)
		root.removeAllChildren()
		moduleNodes.clear()
		val suiteNodes = HashMap<String, DefaultMutableTreeNode>()
		for (c in cases.values) {
			if (!showPassed && c.status == Status.Passed) continue
			val moduleNode = moduleNodes.getOrPut(c.module) { DefaultMutableTreeNode(Module(c.module).also { m -> m.coverage = moduleCoverage[c.module] }).also(root::add) }
			var parent = moduleNode
			for (i in c.suites.indices) {
				val path = c.suites.subList(0, i + 1)
				val key = c.module + "::" + path.joinToString("::")
				parent = suiteNodes.getOrPut(key) { DefaultMutableTreeNode(Suite(c.module, path)).also(parent::add) }
			}
			parent.add(DefaultMutableTreeNode(c))
		}
		model.reload()
		if (expanded.isEmpty()) {
			for (i in 0 until root.childCount) tree.expandPath(TreePath((root.getChildAt(i) as DefaultMutableTreeNode).path))
		} else {
			var row = 0
			while (row < tree.rowCount) {
				val path = tree.getPathForRow(row)
				if (keyOf(path) in expanded) tree.expandPath(path)
				row++
			}
		}
		if (selected != null) select(selected)
	}

	private val moduleCoverage = HashMap<String, Double>()

	private fun keyOf(path: TreePath?): String? = when (val o = (path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
		is Module -> o.name
		is Suite -> o.module + "::" + o.path.joinToString("::")
		is Case -> o.name
		else -> null
	}

	private fun nodeOf(name: String): DefaultMutableTreeNode? {
		val e = root.depthFirstEnumeration()
		while (e.hasMoreElements()) {
			val n = e.nextElement() as DefaultMutableTreeNode
			if ((n.userObject as? Case)?.name == name) return n
		}
		return null
	}

	private fun select(name: String) {
		val n = nodeOf(name) ?: return
		tree.selectionPath = TreePath(n.path)
	}

	private fun selectedObject(): Any? = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject
	private fun selectedCase(): Case? = selectedObject() as? Case

	/** Every test under a node, in tree order. */
	private fun casesUnder(node: DefaultMutableTreeNode): List<Case> {
		val out = ArrayList<Case>()
		val e = node.depthFirstEnumeration()
		while (e.hasMoreElements()) ((e.nextElement() as DefaultMutableTreeNode).userObject as? Case)?.let(out::add)
		return out
	}

	private fun statusOf(node: DefaultMutableTreeNode): Status {
		val tests = casesUnder(node)
		if (tests.isEmpty()) return Status.None
		if (tests.any { it.status == Status.Running }) return Status.Running
		val failed = tests.count { it.status == Status.Failed }
		val passed = tests.count { it.status == Status.Passed }
		return when {
			failed > 0 && passed > 0 -> Status.Mixed
			failed > 0 -> Status.Failed
			passed > 0 -> Status.Passed
			tests.all { it.status == Status.Skipped } -> Status.Skipped
			else -> Status.None
		}
	}

	private fun iconFor(status: Status, kind: Any?): Icon = when (status) {
		Status.Running -> AnimatedIcon.Default.INSTANCE
		Status.Passed -> AllIcons.RunConfigurations.TestPassed
		Status.Failed -> AllIcons.RunConfigurations.TestFailed
		Status.Mixed -> AllIcons.General.Warning
		Status.Skipped -> AllIcons.RunConfigurations.TestIgnored
		Status.None -> when (kind) {
			is Module -> ADMIcons.MODULE
			is Suite -> ADMIcons.CHECK
			else -> AllIcons.RunConfigurations.TestNotRan
		}
	}

	/** Rows: icon, name, and duration (plus coverage for modules) at the right edge. */
	private inner class Renderer : ColoredTreeCellRenderer() {
		private var rightText: String? = null
		private val gap = JBUI.scale(12)

		override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
			val node = value as? DefaultMutableTreeNode ?: return
			rightText = null
			when (val o = node.userObject) {
				is Module -> {
					val status = statusOf(node)
					icon = iconFor(status, o)
					append(o.name)
					val parts = ArrayList<String>()
					o.coverage?.let { parts.add(String.format("%.1f%% cov", it)) }
					duration(node)?.let(parts::add)
					rightText = parts.joinToString("  ").ifEmpty { null }
				}
				is Suite -> {
					icon = iconFor(statusOf(node), o)
					append(o.label)
					rightText = duration(node)
				}
				is Case -> {
					icon = iconFor(o.status, o)
					append(o.leaf, if (o.status == Status.Skipped) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
					o.skipReason?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
					rightText = if (o.status == Status.Passed || o.status == Status.Failed) millis(o.seconds) else null
				}
				else -> append(o?.toString() ?: "")
			}
		}

		private fun duration(node: DefaultMutableTreeNode): String? {
			val done = casesUnder(node).filter { it.status == Status.Passed || it.status == Status.Failed }
			if (done.isEmpty()) return null
			return millis(done.sumOf { it.seconds })
		}

		override fun getPreferredSize(): Dimension {
			val d = super.getPreferredSize()
			rightText?.let { d.width += gap + getFontMetrics(font).stringWidth(it) }
			return d
		}

		override fun paintComponent(g: Graphics) {
			super.paintComponent(g)
			val text = rightText ?: return
			val fm = getFontMetrics(font)
			g.color = if (mySelected && isFocused) UIUtil.getTreeSelectionForeground(true) else UIUtil.getInactiveTextColor()
			g.font = font
			val x = width - fm.stringWidth(text) - JBUI.scale(4)
			g.drawString(text, x, (height + fm.ascent - fm.descent) / 2)
		}
	}

	// --- output ------------------------------------------------------------------------

	private fun showSelected() {
		console.clear()
		val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode
		val o = node?.userObject
		when (o) {
			is Case -> print(o.output)
			is Module, is Suite -> for (c in casesUnder(node)) {
				if (c.output.isEmpty()) continue
				console.print("${c.name}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
				print(c.output)
			}
			else -> {
				print(rootOutput)
				for (c in cases.values) {
					if (c.output.isEmpty() || c.status != Status.Failed) continue
					console.print("${c.name}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
					print(c.output)
				}
			}
		}
		updateStatus()
	}

	private fun print(lines: List<Pair<String, ConsoleViewContentType>>) {
		for ((text, type) in lines) console.print(text, type)
	}

	/** Whether a line for [case] (null: a root line) belongs to the console as shown. */
	private fun visibleNow(case: Case?): Boolean {
		val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode
		val o = node?.userObject
		return when {
			o == null -> case == null || case.status == Status.Failed
			case == null -> false
			o is Case -> o === case
			else -> casesUnder(node).any { it === case }
		}
	}

	private fun updateStatus() {
		val s = summary
		val running = handler != null
		val text = when {
			running && s == null -> statusText.ifEmpty { "Running tests…" }
			s != null && s[1] > 0 -> "${s[1]} of ${s[3]} tests failed" + (if (s[2] > 0) ", ${s[2]} skipped" else "")
			s != null -> "${s[0]} tests passed" + (if (s[2] > 0) ", ${s[2]} skipped" else "")
			lastRunFailed -> statusText.ifEmpty { "Tests failed" }
			else -> statusText
		}
		statusLabel.text = text
		statusLabel.foreground = when {
			s != null && s[1] > 0 -> FAILED_COLOR
			s != null && !running -> PASSED_COLOR
			lastRunFailed && !running -> FAILED_COLOR
			else -> UIUtil.getLabelForeground()
		}
		bar.repaint()
	}

	// --- running -----------------------------------------------------------------------

	private fun runAll() {
		if (handler != null) stop()
		start(emptyList(), null)
	}

	/** Runs the tests of the given modules (empty: all), by their test files. */
	private fun runModules(modules: Set<String>) {
		if (modules.isEmpty()) return start(emptyList(), null)
		val args = ArrayList<String>()
		val names = HashSet<String>()
		for (m in modules) {
			for (f in moduleFiles[m].orEmpty()) args.addAll(listOf("--file", "=$f"))
			cases.values.filter { it.module == m }.mapTo(names) { it.name }
		}
		if (args.isEmpty()) return start(emptyList(), null)
		start(args, names)
	}

	/**
	 * Starts `adm test` with [filters]; [selected] names the tests the run
	 * covers (null: all), which lose their previous result now.
	 */
	private fun start(filters: List<String>, selected: Set<String>?) {
		val dir = scopeDir ?: return
		if (handler != null) return
		val adm = ADMExec.resolve(project.basePath)
		if (adm == null) {
			rootOutput.add("adm executable not found; set it in Settings › Languages › ADM\n" to ConsoleViewContentType.ERROR_OUTPUT)
			showSelected()
			return
		}
		WriteIntentReadAction.run { FileDocumentManager.getInstance().saveAllDocuments() }
		for (c in cases.values) if (selected == null || c.name in selected) c.reset()
		rootOutput.clear()
		pendingCoverage.clear()
		summary = null
		lastRunFailed = false
		stopping = false
		statusText = "Building tests…"
		var args = listOf("test", ".", "--json") + filters
		if (COVERAGE_SUPPORTED && withCoverage) args = args + listOf("--coverage-report")
		val cmd = GeneralCommandLine(adm.toString())
			.withParameters(ADMExec.withBackend(args))
			.withWorkDirectory(dir)
			.withCharset(StandardCharsets.UTF_8)
			.withEnvironment(ADMExec.env())
		val h = try {
			OSProcessHandler(cmd)
		} catch (t: Throwable) {
			rootOutput.add("${t.message ?: t}\n" to ConsoleViewContentType.ERROR_OUTPUT)
			showSelected()
			return
		}
		handler = h
		val partial = StringBuilder()
		h.addProcessListener(object : ProcessAdapter() {
			override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
				if (outputType == ProcessOutputTypes.STDERR) {
					ApplicationManager.getApplication().invokeLater({ if (handler === h) line(null, event.text, ConsoleViewContentType.ERROR_OUTPUT) }, project.disposed)
					return
				}
				if (outputType != ProcessOutputTypes.STDOUT) return
				partial.append(event.text)
				var i = partial.indexOf("\n")
				while (i >= 0) {
					val text = partial.substring(0, i)
					partial.delete(0, i + 1)
					val obj = runCatching { JsonParser.parseString(text) }.getOrNull()?.asObjectOrNull()
					ApplicationManager.getApplication().invokeLater({
						if (handler !== h) return@invokeLater
						if (obj != null) event(obj) else line(null, "$text\n", ConsoleViewContentType.NORMAL_OUTPUT)
					}, project.disposed)
					i = partial.indexOf("\n")
				}
			}

			override fun processTerminated(event: ProcessEvent) {
				ApplicationManager.getApplication().invokeLater({ if (handler === h) finished(event.exitCode) }, project.disposed)
			}
		})
		spinner.start()
		h.startNotify()
		rebuildTree()
		updateStatus()
	}

	private fun stop() {
		val h = handler ?: return
		stopping = true
		h.destroyProcess()
	}

	private fun finished(exitCode: Int) {
		handler = null
		spinner.stop()
		for (c in cases.values) if (c.status == Status.Running) c.status = if (stopping) Status.None else Status.Failed
		if (stopping) {
			statusText = "Stopped"
		} else if (exitCode != 0 && summary == null) {
			lastRunFailed = true
			if (statusText == "Building tests…" || statusText.startsWith("Running")) statusText = "Tests failed to build"
		}
		if (pendingCoverage.isNotEmpty()) {
			ADMTestCoverage.getInstance(project).update(HashMap(pendingCoverage))
			pendingCoverage.clear()
		}
		stopping = false
		tree.repaint()
		updateStatus()
		if (queued) {
			queued = false
			schedule()
		}
	}

	private fun event(o: JsonObject) {
		when (o.str("event")) {
			"status" -> {
				statusText = (o.str("text") ?: "") + "…"
				line(null, "${o.str("text")}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
				updateStatus()
			}
			"diagnostic" -> {
				val type = if (o.str("severity") == "error") ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.LOG_WARNING_OUTPUT
				line(null, "${o.str("file")}:${o.int("line")}:${o.int("column")}: ${o.str("severity")}: ${o.str("message")}\n", type)
			}
			"run" -> {
				val name = o.str("name") ?: return
				val c = cases[name] ?: caseOf(name, name.substringBefore("::"), "", 0).also { cases[name] = it; rebuildTree() }
				c.reset()
				c.status = Status.Running
				statusText = "Running tests…"
				tree.repaint()
				updateStatus()
			}
			"pass", "fail" -> {
				val c = cases[o.str("name") ?: return] ?: return
				c.status = if (o.str("event") == "pass") Status.Passed else Status.Failed
				c.seconds = o.get("seconds")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
				o.str("code")?.let { line(c, "[$it]\n", ConsoleViewContentType.ERROR_OUTPUT) }
				if (!showPassed && c.status == Status.Passed) rebuildTree()
				tree.repaint()
			}
			"skip" -> {
				val c = cases[o.str("name") ?: return] ?: return
				c.status = Status.Skipped
				c.skipReason = o.str("reason")
				tree.repaint()
			}
			"output" -> {
				val c = o.str("name")?.let { cases[it] }
				val type = if (o.str("stream") == "stderr") ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.NORMAL_OUTPUT
				line(c, "${o.str("text")}\n", type)
			}
			"summary" -> {
				summary = intArrayOf(o.int("passed") ?: 0, o.int("failed") ?: 0, o.int("skipped") ?: 0, o.int("selected") ?: 0)
				lastRunFailed = (summary?.get(1) ?: 0) > 0
				updateStatus()
			}
			"coverage" -> {
				for ((module, v) in o.obj("modules")?.entrySet().orEmpty()) {
					val pct = (v as? JsonObject)?.get("percent")?.takeIf { it.isJsonPrimitive }?.asDouble ?: continue
					moduleCoverage[module] = pct
					(moduleNodes[module]?.userObject as? Module)?.coverage = pct
				}
				tree.repaint()
			}
			"file_coverage" -> {
				val file = o.str("file") ?: return
				val lines = o.obj("lines") ?: return
				fun ints(key: String) = lines.arr(key)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asInt }?.toSet().orEmpty()
				pendingCoverage[resolve(file)] = ADMTestCoverage.Lines(ints("full"), ints("partial"), ints("none"))
			}
			"error" -> {
				lastRunFailed = true
				statusText = o.str("text") ?: "Tests failed"
				line(null, "${o.str("text")}\n", ConsoleViewContentType.ERROR_OUTPUT)
				updateStatus()
			}
			"done" -> if (!o.bool("ok")) lastRunFailed = true
		}
	}

	private fun line(case: Case?, text: String, type: ConsoleViewContentType) {
		(case?.output ?: rootOutput).add(text to type)
		if (visibleNow(case)) console.print(text, type)
	}

	// --- auto-run ---------------------------------------------------------------------

	private fun changed(event: DocumentEvent) {
		if (!autoRun) return
		val dir = scopeDir ?: return
		val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
		if (file.extension != "adm") return
		val path = File(file.path).absolutePath
		if (!path.startsWith(File(dir).absolutePath + File.separator)) return
		val module = moduleOfDir[File(path).parent ?: ""]
		if (module == null) dirtyAll = true else dirtyModules.add(module)
		schedule()
	}

	/** Runs the dirty modules once typing has paused; waits for a run in progress. */
	private fun schedule() {
		alarm.cancelAllRequests()
		alarm.addRequest({
			if (!autoRun || (dirtyModules.isEmpty() && !dirtyAll)) return@addRequest
			if (handler != null) {
				queued = true
				return@addRequest
			}
			val all = dirtyAll
			val modules = LinkedHashSet(dirtyModules)
			dirtyAll = false
			dirtyModules.clear()
			if (all) start(emptyList(), null) else runModules(modules)
		}, AUTO_RUN_DELAY_MS)
	}

	// --- navigation ---------------------------------------------------------------------

	private fun openSelected(): Boolean {
		val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return false
		val target = when (val o = node.userObject) {
			is Case -> o
			else -> casesUnder(node).firstOrNull { it.file.isNotEmpty() }
		} ?: return false
		if (target.file.isEmpty()) return false
		val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(resolve(target.file)) ?: return false
		val line = if (node.userObject is Case) target.line else if (node.userObject is Suite) target.line else 1
		OpenFileDescriptor(project, vf, (line - 1).coerceAtLeast(0), 0).navigate(true)
		return true
	}

	override fun dispose() {
		spinner.stop()
		handler?.destroyProcess()
		handler = null
	}

	private companion object {
		const val AUTO_RUN_DELAY_MS = 1500
		// Off for a compiler whose `adm test` cannot measure coverage.
		const val COVERAGE_SUPPORTED = true
		val PASSED_COLOR = JBColor(0x368746, 0x50A661)
		val FAILED_COLOR = JBColor(0xC7222D, 0xE55765)

		fun millis(seconds: Double): String {
			val ms = (seconds * 1000).toLong()
			return when {
				ms < 1000 -> "$ms ms"
				ms < 60_000 -> String.format("%.1f s", ms / 1000.0)
				else -> "${ms / 60_000} min ${(ms % 60_000) / 1000} s"
			}
		}
	}
}
