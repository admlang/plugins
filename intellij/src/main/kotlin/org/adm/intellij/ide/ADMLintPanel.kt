package org.adm.intellij.ide

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
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
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import org.adm.intellij.ADMIcons
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The Lint tab: what `adm lint --json` reports for the selected
 * project, grouped by check or by file, each finding a jump to its line and,
 * on the right, the finding's message, the fix it suggests and what the
 * check is about. Checks can be switched off from the toolbar; the choice
 * persists per project and is passed as `--skip`.
 */
class ADMLintPanel(private val project: Project, parentDisposable: Disposable) : JBPanelWithEmptyText(BorderLayout()), Disposable {
	private class Finding(val file: String, val line: Int, val column: Int, val code: String, val severity: String, val message: String, val fix: String?, val edits: List<Edit>)

	/** One replacement of a fix: 1-based line and character columns, end exclusive. */
	private class Edit(val file: String, val line: Int, val column: Int, val endLine: Int, val endColumn: Int, val text: String)

	private class Group(val label: String, val code: String?, val file: String?) {
		var warnings = 0
		var infos = 0
	}

	private val root = DefaultMutableTreeNode()
	private val model = DefaultTreeModel(root)
	private val tree = Tree(model)
	private val details = JEditorPane("text/html", "").apply {
		// The Swing default HTML style is a size or two below the IDE's labels;
		// the platform kit renders in the label font, code included.
		editorKit = com.intellij.util.ui.HTMLEditorKitBuilder.simple()
		isEditable = false
		border = JBUI.Borders.empty(8, 12)
		background = UIUtil.getPanelBackground()
	}
	private val detailsHost = JBPanelWithEmptyText(BorderLayout()).apply {
		emptyText.text = "Select a finding"
		add(JBScrollPane(details).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
	}
	private val statusLabel = JBLabel("", SwingConstants.LEFT).apply { border = JBUI.Borders.empty(4, 8) }
	private val splitter = OnePixelSplitter(false, 0.55f).apply { splitterProportionKey = "ADM.Lint.split" }

	private var findings: List<Finding> = emptyList()
	private var descriptions: Map<String, String> = emptyMap()
	private var scopeDir: String? = null
	private var running = false
	private var error: String? = null

	private var byFile: Boolean
		get() = PropertiesComponent.getInstance(project).getBoolean("ADM.Lint.byFile", false)
		set(value) = PropertiesComponent.getInstance(project).setValue("ADM.Lint.byFile", value, false)

	/** Lint from the workspace root (every unit, the standard library when it lives here) instead of the selected project. */
	private var wholeWorkspace: Boolean
		get() = PropertiesComponent.getInstance(project).getBoolean("ADM.Lint.workspace", false)
		set(value) = PropertiesComponent.getInstance(project).setValue("ADM.Lint.workspace", value, false)

	private fun skipped(code: String): Boolean = PropertiesComponent.getInstance(project).getBoolean("ADM.Lint.skip.$code", false)
	private fun setSkipped(code: String, value: Boolean) = PropertiesComponent.getInstance(project).setValue("ADM.Lint.skip.$code", value, false)

	init {
		Disposer.register(parentDisposable, this)
		tree.isRootVisible = false
		tree.showsRootHandles = true
		tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
		tree.emptyText.text = "Run the checks"
		tree.cellRenderer = object : ColoredTreeCellRenderer() {
			override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
				when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
					is Group -> {
						icon = when {
							o.file != null -> ADMIcons.FILE
							o.code == BUILD_ERROR -> AllIcons.General.Error
							o.warnings > 0 -> AllIcons.General.Warning
							else -> AllIcons.General.Information
						}
						append(o.label)
						val parts = ArrayList<String>()
						if (o.warnings > 0) parts.add("${o.warnings} warning" + if (o.warnings > 1) "s" else "")
						if (o.infos > 0) parts.add("${o.infos} info")
						append("  " + parts.joinToString(", "), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
					}
					is Finding -> {
						icon = when (o.severity) {
							"error" -> AllIcons.General.Error
							"warning" -> AllIcons.General.Warning
							else -> AllIcons.General.Information
						}
						if (byFile) append("[${o.code}] ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
						append(o.message)
						append("  ${File(o.file).name}:${o.line}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
					}
					else -> append(o?.toString() ?: "")
				}
			}
		}
		tree.addTreeSelectionListener { showSelected() }
		object : DoubleClickListener() {
			override fun onDoubleClick(event: MouseEvent): Boolean = openSelected()
		}.installOn(tree)
		tree.addKeyListener(object : KeyAdapter() {
			override fun keyPressed(e: KeyEvent) {
				if (e.keyCode == KeyEvent.VK_ENTER && openSelected()) e.consume()
			}
		})

		val run = object : DumbAwareAction("Run Checks", "Run adm lint on the selected project", AllIcons.Actions.Execute) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.icon = if (running) AnimatedIcon.Default.INSTANCE else if (findings.isNotEmpty()) AllIcons.Actions.Rerun else AllIcons.Actions.Execute
				e.presentation.isEnabled = !running && scopeDir != null
			}
			override fun actionPerformed(e: AnActionEvent) = run()
		}
		val fix = object : DumbAwareAction("Fix", "Apply the selected finding's fix, or every fix in the selected group, then run the checks again", AllIcons.Actions.IntentionBulb) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				val n = fixable().size
				e.presentation.isEnabled = !running && n > 0
				e.presentation.text = when {
					n > 1 -> "Fix ($n)"
					else -> "Fix"
				}
				e.presentation.description = when (selectedObject()) {
					is Finding -> if (n > 0) "Apply this finding's fix, then run the checks again" else "This finding has no automatic fix"
					is Group -> if (n > 0) "Apply the $n fixes in this group, then run the checks again" else "No finding in this group has an automatic fix"
					else -> "Select a finding or a group with a fix"
				}
			}
			override fun actionPerformed(e: AnActionEvent) = applyFixes(fixable())
		}
		val workspaceScope = object : ToggleAction("Whole Workspace", "Lint every file under the workspace root, not only the selected project; in the compiler repository that includes the standard library", AllIcons.Nodes.ModuleGroup), DumbAware {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun isSelected(e: AnActionEvent) = wholeWorkspace
			override fun setSelected(e: AnActionEvent, state: Boolean) {
				wholeWorkspace = state
				scopeDir = null
				scopeTo(ADMWorkspace.getInstance(project).selected)
			}
		}
		val grouping = object : ToggleAction("Group by File", "Group findings by file instead of by check", AllIcons.Actions.GroupByFile), DumbAware {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun isSelected(e: AnActionEvent) = byFile
			override fun setSelected(e: AnActionEvent, state: Boolean) {
				byFile = state
				rebuild()
			}
		}
		val checks = object : DefaultActionGroup("Checks", "Which checks run", AllIcons.General.Filter), DumbAware {
			init {
				isPopup = true
				templatePresentation.isPerformGroup = false
			}
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun getChildren(e: AnActionEvent?): Array<com.intellij.openapi.actionSystem.AnAction> =
				CODES.map { code ->
					object : ToggleAction(code, descriptions[code] ?: "", null), DumbAware {
						override fun getActionUpdateThread() = ActionUpdateThread.EDT
						override fun isSelected(e: AnActionEvent) = !skipped(code)
						override fun setSelected(e: AnActionEvent, state: Boolean) = setSkipped(code, !state)
					}
				}.toTypedArray()
		}
		val expand = object : DumbAwareAction("Expand All", null, AllIcons.Actions.Expandall) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
		}
		val collapse = object : DumbAwareAction("Collapse All", null, AllIcons.Actions.Collapseall) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun actionPerformed(e: AnActionEvent) = TreeUtil.collapseAll(tree, 1)
		}
		val toolbar = ActionManager.getInstance().createActionToolbar("ADMLint", DefaultActionGroup(run, fix, Separator.getInstance(), workspaceScope, grouping, checks, Separator.getInstance(), expand, collapse), true)
		toolbar.targetComponent = this

		// Right-click on a finding or a group: the same fix, plus the jump.
		val jump = object : DumbAwareAction("Jump to Source", "Open the finding in the editor", AllIcons.Actions.EditSource) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.isEnabled = selectedObject() is Finding
			}
			override fun actionPerformed(e: AnActionEvent) {
				openSelected()
			}
		}
		val fixAll = object : DumbAwareAction("Fix All in Group", "Apply every fix under the selected group", AllIcons.Actions.IntentionBulbGrey) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.isEnabledAndVisible = selectedObject() is Group && fixable().isNotEmpty()
			}
			override fun actionPerformed(e: AnActionEvent) = applyFixes(fixable())
		}
		com.intellij.ui.PopupHandler.installPopupMenu(tree, DefaultActionGroup(fix, fixAll, Separator.getInstance(), jump), "ADMLintPopup")
		details.addHyperlinkListener { e ->
			if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
				when (e.description) {
					"fix" -> applyFixes(fixable())
					"open" -> openSelected()
				}
			}
		}

		val left = JPanel(BorderLayout()).apply {
			add(toolbar.component, BorderLayout.NORTH)
			add(JBScrollPane(tree).apply { border = JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor()) }, BorderLayout.CENTER)
		}
		val right = JPanel(BorderLayout()).apply {
			add(statusLabel, BorderLayout.NORTH)
			add(detailsHost, BorderLayout.CENTER)
		}
		splitter.firstComponent = left
		splitter.secondComponent = right
		add(splitter, BorderLayout.CENTER)
		emptyText.text = "Select a project to check its code"

		ADMWorkspace.getInstance(project).addListener(object : ADMWorkspace.Listener {
			override fun selectionChanged(unit: ADMUnit?) = scopeTo(unit)
			override fun unitsChanged(units: List<ADMUnit>) {
				if (scopeDir == null) scopeTo(ADMWorkspace.getInstance(project).selected)
			}
		}, this)
		scopeTo(ADMWorkspace.getInstance(project).selected)
		loadDescriptions()
	}

	private fun loadDescriptions() {
		ADMCli.async(project, listOf("lint", "--list")) { r ->
			if (!r.ok) return@async
			descriptions = r.stdout.lineSequence().mapNotNull { line ->
				val t = line.trim()
				val sp = t.indexOfFirst { it.isWhitespace() }
				if (sp <= 0) null else t.substring(0, sp) to t.substring(sp).trim()
			}.toMap()
			showSelected()
		}
	}

	private fun scopeTo(unit: ADMUnit?) {
		val dir = if (wholeWorkspace) project.basePath else unit?.projectDir ?: project.basePath
		if (dir == scopeDir) return
		scopeDir = dir
		findings = emptyList()
		error = null
		rebuild()
		updateStatus()
	}

	/**
	 * Runs the checks. The language server, when it is up, answers from the
	 * analysis it keeps current as files change, so the tab fills at once;
	 * without a server the CLI type-checks the scope from scratch.
	 */
	private fun run() {
		val dir = scopeDir ?: return
		if (running) return
		val server = com.intellij.platform.lsp.api.LspServerManager.getInstance(project)
			.getServersForProvider(org.adm.intellij.lsp.ADMLspServerSupportProvider::class.java).firstOrNull()
		if (server == null) {
			runCli(dir)
			return
		}
		running = true
		error = null
		updateStatus()
		com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
			val result = runCatching {
				server.sendRequestSync(60_000) { ls -> (ls as org.adm.intellij.lsp.ADMLanguageServer).admLint(org.adm.intellij.lsp.ADMLintParams(dir)) }
			}.getOrNull()
			com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
				if (scopeDir != dir) return@invokeLater
				running = false
				if (result == null) {
					// No answer (older server, timeout): the CLI still knows.
					runCli(dir)
					return@invokeLater
				}
				val skip = CODES.filter { skipped(it) }.toSet()
				val errors = result.errors.orEmpty().mapNotNull { fromWire(it) }
				findings = errors + result.findings.orEmpty().mapNotNull { fromWire(it) }.filter { it.code !in skip }
				error = if (errors.isEmpty()) null else "the program has ${errors.size} error(s); findings may be incomplete"
				rebuild()
				updateStatus()
			}, project.disposed)
		}
	}

	private fun fromWire(w: org.adm.intellij.lsp.ADMLintFinding): Finding? {
		val file = w.file ?: return null
		val code = w.code ?: return null
		val edits = w.edits.orEmpty().mapNotNull { e -> Edit(e.file ?: return@mapNotNull null, e.line, e.column, e.endLine, e.endColumn, e.text ?: "") }
		val build = code == "build-error"
		return Finding(file, w.line, w.column, if (build) BUILD_ERROR else code, w.severity ?: "info", w.message ?: "", if (build) "fix the error, then run the checks again" else w.fix, edits)
	}

	/** The CLI route: `adm lint . --json` in the scope directory. */
	private fun runCli(dir: String) {
		if (running) return
		running = true
		error = null
		WriteIntentReadAction.run { FileDocumentManager.getInstance().saveAllDocuments() }
		val args = ArrayList(listOf("lint", ".", "--json"))
		val skip = CODES.filter { skipped(it) }
		if (skip.isNotEmpty()) args.addAll(listOf("--skip", skip.joinToString(",")))
		updateStatus()
		ADMCli.async(project, args, dir) { r ->
			running = false
			if (scopeDir != dir) return@async
			val json = r.json()
			val obj = json.asObjectOrNull()
			findings = when {
				obj != null && obj.str("error") != null -> {
					// The program did not type-check: the errors become entries
					// of their own, so they can be opened like any finding.
					error = obj.str("error")
					obj.arr("diagnostics").objects().mapNotNull { d ->
						val file = d.str("file") ?: return@mapNotNull null
						Finding(file, d.int("line") ?: 1, d.int("column") ?: 1, BUILD_ERROR, "error", d.str("message") ?: "", "fix the error, then run the checks again", emptyList())
					}
				}
				!r.ok -> {
					error = r.failure()
					emptyList()
				}
				else -> json.asArrayOrNull().objects().mapNotNull(::parse)
			}
			rebuild()
			updateStatus()
		}
	}

	private fun parse(o: JsonObject): Finding? {
		val file = o.str("file") ?: return null
		val edits = o.arr("edits").objects().mapNotNull { e ->
			Edit(e.str("file") ?: return@mapNotNull null, e.int("line") ?: 1, e.int("column") ?: 1, e.int("endLine") ?: 1, e.int("endColumn") ?: 1, e.str("text") ?: "")
		}
		return Finding(file, o.int("line") ?: 1, o.int("column") ?: 1, o.str("code") ?: "", o.str("severity") ?: "info", o.str("message") ?: "", o.str("fix"), edits)
	}

	/** The fixable findings the selection covers: the finding itself, or every one under a group. */
	private fun fixable(): List<Finding> {
		val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return emptyList()
		return when (val o = node.userObject) {
			is Finding -> if (o.edits.isNotEmpty()) listOf(o) else emptyList()
			is Group -> (0 until node.childCount).mapNotNull { (node.getChildAt(it) as DefaultMutableTreeNode).userObject as? Finding }.filter { it.edits.isNotEmpty() }
			else -> emptyList()
		}
	}

	/**
	 * Applies the findings' edits to the documents, last position first per
	 * file so earlier offsets stay valid, then runs the checks again. Two
	 * findings whose edits overlap are refused: fix one, lint, fix the next.
	 */
	private fun applyFixes(findings: List<Finding>) {
		val byFile = findings.flatMap { it.edits }.groupBy { resolve(it.file) }
		val docs = HashMap<String, com.intellij.openapi.editor.Document>()
		for (path in byFile.keys) {
			val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
			val doc = vf?.let { FileDocumentManager.getInstance().getDocument(it) }
			if (doc == null) {
				statusLabel.text = "Cannot open $path"
				return
			}
			docs[path] = doc
		}
		WriteCommandAction.runWriteCommandAction(project, "Apply Lint Fix", null, {
			for ((path, edits) in byFile) {
				val doc = docs[path] ?: continue
				fun offset(line: Int, column: Int): Int {
					val l = (line - 1).coerceIn(0, doc.lineCount - 1)
					return (doc.getLineStartOffset(l) + column - 1).coerceIn(doc.getLineStartOffset(l), doc.getLineEndOffset(l).let { end -> if (line - 1 < doc.lineCount - 1) end + 1 else end })
				}
				val ranges = edits.map { Triple(offset(it.line, it.column), offset(it.endLine, it.endColumn), it.text) }.sortedByDescending { it.first }
				for (i in 1 until ranges.size) {
					if (ranges[i].second > ranges[i - 1].first) {
						statusLabel.text = "Two fixes overlap in ${File(path).name}; fix one, run the checks, then the other"
						return@runWriteCommandAction
					}
				}
				for ((start, end, text) in ranges) doc.replaceString(start, end, text)
			}
			FileDocumentManager.getInstance().saveAllDocuments()
		})
		run()
	}

	private fun rebuild() {
		root.removeAllChildren()
		val groups = LinkedHashMap<String, DefaultMutableTreeNode>()
		val ordered = if (byFile) findings.sortedWith(compareBy({ it.file }, { it.line })) else findings.sortedWith(compareBy({ if (it.code == BUILD_ERROR) -1 else CODES.indexOf(it.code) }, { it.file }, { it.line }))
		for (f in ordered) {
			val key = if (byFile) f.file else f.code
			val node = groups.getOrPut(key) {
				val g = if (byFile) Group(relative(f.file), null, f.file) else Group(f.code, f.code, null)
				DefaultMutableTreeNode(g).also(root::add)
			}
			val g = node.userObject as Group
			if (f.severity == "warning") g.warnings++ else g.infos++
			node.add(DefaultMutableTreeNode(f))
		}
		model.reload()
		// Warnings open, the informational groups stay folded.
		for (i in 0 until root.childCount) {
			val n = root.getChildAt(i) as DefaultMutableTreeNode
			val g = n.userObject as Group
			if (g.warnings > 0 || g.code == BUILD_ERROR || byFile) tree.expandPath(TreePath(n.path))
		}
	}

	private fun relative(path: String): String {
		val dir = scopeDir ?: return path
		val abs = resolve(path)
		return abs.removePrefix(File(dir).absolutePath + File.separator)
	}

	private fun resolve(path: String): String {
		val f = File(path)
		return if (f.isAbsolute) f.absolutePath else File(scopeDir ?: project.basePath ?: ".", path).absolutePath
	}

	private fun selectedObject(): Any? = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject

	private fun showSelected() {
		val o = selectedObject()
		val code = when (o) {
			is Finding -> o.code
			is Group -> o.code
			else -> null
		}
		val b = StringBuilder("<html><body>")
		if (o is Finding) {
			b.append("<p><b>").append(esc(o.message)).append("</b></p>")
			o.fix?.let { b.append("<p>").append(esc(it)).append("</p>") }
			if (o.edits.isNotEmpty()) {
				b.append("<p><a href='fix'>Apply fix</a> to ").append(esc(o.edits.map { File(it.file).name }.distinct().joinToString(", "))).append(" &nbsp; <a href='open'>Jump to source</a></p>")
			} else {
				b.append("<p style='color:gray'>No automatic fix for this finding. &nbsp; <a href='open'>Jump to source</a></p>")
			}
			b.append("<p style='color:gray'>").append(esc(relative(o.file))).append(':').append(o.line).append(':').append(o.column).append("</p>")
		}
		if (code == BUILD_ERROR) {
			b.append("<p style='color:gray'>The checks need a program that type-checks; this error stopped them.</p>")
		} else if (code != null) {
			b.append("<p style='color:gray'><code>").append(esc(code)).append("</code> ").append(esc(descriptions[code] ?: "")).append("</p>")
		}
		b.append("</body></html>")
		details.text = if (o == null) "" else b.toString()
		details.caretPosition = 0
	}

	private fun updateStatus() {
		val warnings = findings.count { it.severity == "warning" }
		val infos = findings.size - warnings
		val where = if (wholeWorkspace) "the workspace" else ADMWorkspace.getInstance(project).selected?.name ?: "the workspace"
		statusLabel.text = when {
			running -> "Checking $where…"
			error != null -> error
			findings.isEmpty() && scopeDir != null -> "Nothing to report in $where"
			else -> "$warnings warning" + (if (warnings == 1) "" else "s") + ", $infos info in $where"
		}
		statusLabel.foreground = when {
			error != null -> JBColor(0xC7222D, 0xE55765)
			warnings > 0 -> JBColor(0xB08800, 0xE6B35C)
			else -> UIUtil.getLabelForeground()
		}
	}

	private fun openSelected(): Boolean {
		val f = selectedObject() as? Finding ?: return false
		val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(resolve(f.file)) ?: return false
		OpenFileDescriptor(project, vf, (f.line - 1).coerceAtLeast(0), (f.column - 1).coerceAtLeast(0)).navigate(true)
		return true
	}

	private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

	override fun dispose() {}

	private companion object {
		/** The checks, in the order `adm lint` groups them. */
		val CODES = org.adm.intellij.lsp.ADMLintCodes.ALL

		/** The group holding the type errors that stopped a run. */
		const val BUILD_ERROR = "build-error"
	}
}
