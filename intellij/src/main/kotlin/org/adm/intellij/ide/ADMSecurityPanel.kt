package org.adm.intellij.ide

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import org.adm.intellij.ADMIcons
import org.adm.intellij.documentation.ADMDocTarget
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The Security tab: what the selected unit's code reaches (`adm audit`),
 * permission by permission down to the call chain, the rules of the
 * application's policy.toml beside it (edited through `adm app policy
 * set/unset`), the foreign-code files, and the application's policy
 * decision log (`adm app policy log`), tailed while the application runs.
 */
/** What the install checklist says beside a package's C bindings; the tree says the same. */
private const val FOREIGN_DESCRIPTION = "This library binds C code the compiler cannot check. Accepting it means trusting the library completely."
private const val WHOLE_PERMISSION_HINT = "Applies to everything this permission covers. Use it for permissions that have no fields."
private val WHOLE_PERMISSION_KEYS = setOf("*.allow", "*.deny", "*.ask")

class ADMSecurityPanel(private val project: Project, parentDisposable: Disposable, private val services: ADMServicesPanel) :
	JBPanelWithEmptyText(BorderLayout()), Disposable {

	// --- model ---------------------------------------------------------------

	/** One requirement of one package, as `adm audit --json` reports it. */
	private class Requirement(val permission: String, val observe: Boolean, val via: List<String>, val sites: List<Site?>, val description: String, val fields: List<String>)
	private class Site(val file: String, val line: Int)
	private class Audited(val pkg: String, val requirements: List<Requirement>, val foreign: List<String>)

	/** One line of policy.toml, as `adm app policy get --json` reports it. */
	/** [placeholder] rows are not in the file: one per permission without a `*` rule, offered so a permission can be ruled on as a whole. */
	private class Rule(val subject: String, val permission: String, val field: String, val kind: String, val values: List<String>, val placeholder: Boolean = false) {
		val key: String
			get() = if (kind.isEmpty()) this.field else "${this.field}.$kind"
	}
	private class Policy(val path: String, val exists: Boolean, val rules: List<Rule>)

	/** One line of policy.log: `time outcome permission subject=… [field=…] reason`. */
	private class Decision(val time: String, val outcome: String, val permission: String, val subject: String, val field: String, val reason: String)

	private var unit: ADMUnit? = null
	private var audited: List<Audited> = emptyList()
	private var policy: Policy? = null

	// --- permissions section ---------------------------------------------------

	private val treeRoot = DefaultMutableTreeNode()
	private val treeModel = DefaultTreeModel(treeRoot)
	private val tree = Tree(treeModel)
	private val policyPath = JBLabel("")
	private val policyInit = ActionLink("Init policy") { initPolicy() }
	private val policyNote = JBLabel("").apply { foreground = UIUtil.getContextHelpForeground() }
	private val rulesModel = object : DefaultTableModel(arrayOf("Subject", "Permission", "Rule", "Values"), 0) {
		override fun isCellEditable(row: Int, column: Int) = column == 3 || (column == 2 && rules.getOrNull(row)?.placeholder == true)
	}
	private val rulesTable = JBTable(rulesModel)
	private var rules: List<Rule> = emptyList()
	private var rulesFilter: String? = null
	private var auditStatus = JBLabel("").apply { foreground = UIUtil.getContextHelpForeground() }

	// --- log section -------------------------------------------------------------

	private val logModel = object : DefaultTableModel(arrayOf("Time", "Decision", "Permission", "Subject", "Field", "Reason"), 0) {
		override fun isCellEditable(row: Int, column: Int) = false
	}
	private val logTable = JBTable(logModel)
	private val logStatus = JBLabel("").apply { foreground = UIUtil.getContextHelpForeground() }
	private var decisions: List<Decision> = emptyList()
	private var live = true
	/** Whether `ignore` lines (policy.toml tables for permissions this build does not know) are listed. */
	private var showIgnored = true
	private val poll = Timer(2000) { if (isShowing && shouldTail()) loadLog(quiet = true) }

	private val sections = ADMSectionsView("ADM.Security.sections")

	init {
		Disposer.register(parentDisposable, this)
		tree.isRootVisible = false
		tree.showsRootHandles = true
		tree.cellRenderer = object : ColoredTreeCellRenderer() {
			override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
				when (val x = (value as? DefaultMutableTreeNode)?.userObject) {
					is PermissionNode -> {
						icon = if (x.observe) AllIcons.Nodes.Weblistener else AllIcons.Nodes.Padlock
						append(x.permission)
						if (x.description.isNotEmpty()) append("  ${x.description}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
					}
					is PackageNode -> {
						icon = ADMIcons.LIBRARY
						append(x.pkg)
						append("  ${x.chains} chain(s)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
					}
					is ChainNode -> {
						icon = AllIcons.Nodes.Method
						append(x.label)
						if (x.index == 0) append("  ← entry", SimpleTextAttributes.GRAYED_ATTRIBUTES)
					}
					is ForeignNode -> {
						icon = AllIcons.Nodes.Folder
						append("${x.pkg} binds C code: ${x.files.joinToString(", ")}")
						append("  $FOREIGN_DESCRIPTION", SimpleTextAttributes.GRAYED_ATTRIBUTES)
					}
					is ForeignFile -> {
						icon = ADMIcons.FILE
						append(x.path)
					}
					else -> append(value?.toString().orEmpty())
				}
			}
		}
		tree.addTreeSelectionListener {
			val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
			val permission = generateSequence(node) { it.parent as? DefaultMutableTreeNode }.mapNotNull { (it.userObject as? PermissionNode)?.permission }.firstOrNull()
			if (permission != rulesFilter) {
				rulesFilter = permission
				showRules()
			}
		}
		tree.addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				if (e.clickCount == 2) openSelectedNode()
			}
		})
		tree.addKeyListener(object : KeyAdapter() {
			override fun keyPressed(e: KeyEvent) {
				if (e.keyCode == KeyEvent.VK_ENTER) openSelectedNode()
			}
		})
		tree.emptyText.text = "No permissions"

		rulesTable.setShowGrid(false)
		rulesTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
		rulesTable.emptyText.text = "No rules"
		rulesModel.addTableModelListener { e ->
			if ((e.column == 3 || e.column == 2) && e.firstRow >= 0 && e.firstRow < rules.size) commitValues(e.firstRow)
		}
		// A whole-permission placeholder is greyed until it is filled in.
		rulesTable.setDefaultRenderer(Any::class.java, object : javax.swing.table.DefaultTableCellRenderer() {
			override fun getTableCellRendererComponent(table: javax.swing.JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): java.awt.Component {
				val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
				val placeholder = rules.getOrNull(row)?.placeholder == true
				c.foreground = if (placeholder && !isSelected) UIUtil.getContextHelpForeground() else if (isSelected) table.selectionForeground else table.foreground
				toolTipText = if (placeholder) WHOLE_PERMISSION_HINT else null
				return c
			}
		})
		val rulesPane = ToolbarDecorator.createDecorator(rulesTable)
			.setAddAction { ruleDialog(null) }
			.setEditAction { rules.getOrNull(rulesTable.selectedRow)?.let { ruleDialog(it) } }
			.setEditActionUpdater { rulesTable.selectedRow >= 0 }
			.setRemoveAction { removeRule() }
			.createPanel()
		val policyHeader = JPanel(BorderLayout()).apply {
			border = JBUI.Borders.empty(0, 0, 4, 0)
			add(policyPath, BorderLayout.CENTER)
			add(policyInit, BorderLayout.EAST)
		}
		val policyPage = JPanel(BorderLayout()).apply {
			border = JBUI.Borders.empty(8, 8, 8, 8)
			add(panel {
				row { cell(JBLabel("Policy rules").apply { font = JBUI.Fonts.label().asBold() }) }
				row { cell(policyHeader).align(Align.FILL) }
				row { cell(policyNote) }
			}, BorderLayout.NORTH)
			add(rulesPane, BorderLayout.CENTER)
		}
		val treePage = JPanel(BorderLayout()).apply {
			add(auditStatus.apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.NORTH)
			add(JBScrollPane(tree).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
		}
		val permissionsPage = OnePixelSplitter(false, "ADM.Security.permissions", 0.5f).apply {
			firstComponent = treePage
			secondComponent = policyPage
		}
		val permissionsToolbar = toolbar("ADMSecurityPermissions", DefaultActionGroup(
			object : DumbAwareAction("Refresh", "Run adm audit again and re-read policy.toml", AllIcons.Actions.Refresh) {
				override fun getActionUpdateThread() = ActionUpdateThread.EDT
				override fun actionPerformed(e: AnActionEvent) {
					loadAudit()
					loadPolicy()
				}
			},
			object : DumbAwareAction("Expand All", null, AllIcons.Actions.Expandall) {
				override fun getActionUpdateThread() = ActionUpdateThread.EDT
				override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
			},
			object : DumbAwareAction("Collapse All", null, AllIcons.Actions.Collapseall) {
				override fun getActionUpdateThread() = ActionUpdateThread.EDT
				override fun actionPerformed(e: AnActionEvent) = TreeUtil.collapseAll(tree, 1)
			},
		), permissionsPage)
		sections.add("Permissions", AllIcons.Nodes.SecurityRole, JPanel(BorderLayout()).apply {
			add(permissionsToolbar, BorderLayout.NORTH)
			add(permissionsPage, BorderLayout.CENTER)
		}, scroll = false)

		logTable.setShowGrid(false)
		logTable.emptyText.text = "No decisions logged"
		logTable.columnModel.getColumn(0).preferredWidth = JBUI.scale(170)
		logTable.columnModel.getColumn(1).preferredWidth = JBUI.scale(70)
		logTable.columnModel.getColumn(2).preferredWidth = JBUI.scale(170)
		logTable.columnModel.getColumn(3).preferredWidth = JBUI.scale(120)
		logTable.columnModel.getColumn(4).preferredWidth = JBUI.scale(80)
		logTable.columnModel.getColumn(5).preferredWidth = JBUI.scale(320)
		logTable.addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				if (e.clickCount == 2) revealDecision(logTable.selectedRow)
			}
		})
		val logPage = JPanel(BorderLayout()).apply {
			add(logStatus.apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.NORTH)
			add(JBScrollPane(logTable).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
		}
		val logToolbar = toolbar("ADMSecurityLog", DefaultActionGroup(
			object : DumbAwareAction("Refresh", "Read policy.log again", AllIcons.Actions.Refresh) {
				override fun getActionUpdateThread() = ActionUpdateThread.EDT
				override fun actionPerformed(e: AnActionEvent) = loadLog(quiet = false)
			},
			object : ToggleAction("Tail While Running", "Re-read the log every two seconds while the application runs", AllIcons.Actions.RerunAutomatically) {
				override fun getActionUpdateThread() = ActionUpdateThread.EDT
				override fun isSelected(e: AnActionEvent) = live
				override fun setSelected(e: AnActionEvent, state: Boolean) {
					live = state
				}
			},
			object : ToggleAction("Show Ignored Tables", "List the ignore lines: policy.toml tables for permissions this build does not know", AllIcons.Actions.Show) {
				override fun getActionUpdateThread() = ActionUpdateThread.EDT
				override fun isSelected(e: AnActionEvent) = showIgnored
				override fun setSelected(e: AnActionEvent, state: Boolean) {
					showIgnored = state
					showLog()
				}
			},
			object : DumbAwareAction("Clear", "Delete policy.log (adm app policy log --clear)", AllIcons.Actions.GC) {
				override fun getActionUpdateThread() = ActionUpdateThread.EDT
				override fun update(e: AnActionEvent) {
					e.presentation.isEnabled = decisions.isNotEmpty()
				}
				override fun actionPerformed(e: AnActionEvent) = clearLog()
			},
		), logPage)
		sections.add("Policy log", AllIcons.Nodes.LogFolder, JPanel(BorderLayout()).apply {
			add(logToolbar, BorderLayout.NORTH)
			add(logPage, BorderLayout.CENTER)
		}, scroll = false)
		sections.select(null)
		add(sections, BorderLayout.CENTER)
		emptyText.text = "Select a project"

		val workspace = ADMWorkspace.getInstance(project)
		workspace.addListener(object : ADMWorkspace.Listener {
			override fun selectionChanged(unit: ADMUnit?) = bind(unit)
		}, this)
		bind(workspace.selected)
		poll.start()
	}

	private fun toolbar(place: String, group: DefaultActionGroup, target: JComponent): JComponent {
		val bar = ActionManager.getInstance().createActionToolbar(place, group, true)
		bar.targetComponent = target
		return bar.component
	}

	private fun bind(unit: ADMUnit?) {
		this.unit = unit
		sections.isVisible = unit != null
		if (unit == null) return
		val application = unit.kind == ADMUnit.Kind.Application
		policyNote.text = if (application) "Rules are written to policy.toml through adm app policy set; edit the values in place, or add, edit and remove rows." else "Policy rules belong to an application; a library's permissions are granted by the application that installs it."
		policyInit.isVisible = false
		loadAudit()
		if (application) {
			loadPolicy()
			loadLog(quiet = true)
		} else {
			policy = null
			policyPath.text = ""
			showRules()
			decisions = emptyList()
			showLog()
			logStatus.text = "The decision log belongs to an application."
		}
	}

	// --- audit -----------------------------------------------------------------

	private fun loadAudit() {
		val u = unit ?: return
		auditStatus.text = "Auditing…"
		val args = arrayListOf("audit", u.projectDir, "--json")
		if (u.kind == ADMUnit.Kind.Library) args += listOf("--library", u.name)
		ADMCli.async(project, args, u.projectDir) { r ->
			if (unit !== u) return@async
			val list = r.json().asArrayOrNull()
			if (list == null) {
				auditStatus.text = r.failure()
				audited = emptyList()
			} else {
				audited = list.objects().map { o ->
					Audited(
						pkg = o.str("package") ?: "",
						requirements = o.arr("requirements").objects().map { q ->
							Requirement(
								q.str("permission") ?: "", q.bool("observe"), q.arr("via").strings(),
								q.arr("sites").objects().map { s -> s.str("file")?.takeIf { it.isNotEmpty() }?.let { Site(it, s.int("line") ?: 1) } },
								q.str("description") ?: "", q.arr("fields").strings(),
							)
						},
						foreign = o.arr("foreign").strings(),
					)
				}
				val permissions = audited.flatMap { it.requirements }.map { it.permission }.distinct().size
				auditStatus.text = if (permissions == 0) "The code reaches no gated capability." else "$permissions permission(s) reached by ${audited.size} package(s)."
			}
			showTree()
		}
	}

	private class PermissionNode(val permission: String, val observe: Boolean, val description: String)
	private class PackageNode(val pkg: String, val chains: Int)
	private class ChainNode(val label: String, val index: Int, val site: Site? = null)
	private class ForeignNode(val pkg: String, val files: List<String>)
	private class ForeignFile(val pkg: String, val path: String)

	/** permission → package → chain entries (outermost first), then the foreign-code files. */
	private fun showTree() {
		treeRoot.removeAllChildren()
		val byPermission = LinkedHashMap<String, MutableList<Pair<Audited, Requirement>>>()
		for (a in audited) for (q in a.requirements) byPermission.getOrPut(q.permission) { ArrayList() }.add(a to q)
		for ((permission, entries) in byPermission.entries.sortedBy { it.key }) {
			val first = entries.first().second
			val pNode = DefaultMutableTreeNode(PermissionNode(permission, entries.all { it.second.observe }, first.description))
			for ((pkg, group) in entries.groupBy { it.first.pkg }) {
				val pkgNode = DefaultMutableTreeNode(PackageNode(pkg, group.size))
				for ((_, q) in group) {
					if (q.via.isEmpty()) continue
					val chainNode = DefaultMutableTreeNode(ChainNode(q.via.joinToString(" → ") + if (q.observe) "  (@on)" else "", -1))
					q.via.forEachIndexed { i, label -> chainNode.add(DefaultMutableTreeNode(ChainNode(label, i, q.sites.getOrNull(i)))) }
					pkgNode.add(chainNode)
				}
				pNode.add(pkgNode)
			}
			treeRoot.add(pNode)
		}
		// Foreign code is not a permission: one node per package that binds C,
		// worded as the install checklist words it.
		for (a in audited) {
			if (a.foreign.isEmpty()) continue
			val fNode = DefaultMutableTreeNode(ForeignNode(a.pkg, a.foreign))
			for (f in a.foreign) fNode.add(DefaultMutableTreeNode(ForeignFile(a.pkg, f)))
			treeRoot.add(fNode)
		}
		treeModel.reload()
		TreeUtil.expand(tree, 1)
	}

	/** A chain entry goes to its declaration through the language server; a foreign file opens directly. */
	private fun openSelectedNode() {
		val u = unit ?: return
		when (val x = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject) {
			is ChainNode -> if (x.index >= 0) {
				// The audit locates each hop; the language server is the fallback
				// for a hop without a source file.
				val site = x.site
				if (site != null) {
					val file = File(site.file).let { if (it.isAbsolute) it else File(u.projectDir, site.file) }
					val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
					if (vf != null) {
						OpenFileDescriptor(project, vf, (site.line - 1).coerceAtLeast(0), 0).navigate(true)
						return
					}
				}
				navigateToLabel(x.label)
			}
			is ForeignFile -> {
				val file = File(x.path).let { if (it.isAbsolute) it else File(u.projectDir, x.path) }
				val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return notify("${x.path}: file not found", NotificationType.WARNING)
				OpenFileDescriptor(project, vf).navigate(true)
			}
		}
	}

	private fun navigateToLabel(label: String) {
		ApplicationManager.getApplication().executeOnPooledThread {
			val target = ADMDocTarget.find(project, label)
			ApplicationManager.getApplication().invokeLater({
				if (target == null) notify("$label: no declaration found through the language server", NotificationType.WARNING)
				else target.navigatable.navigate(true)
			}, project.disposed)
		}
	}

	// --- policy ----------------------------------------------------------------

	private fun policyArgs(vararg rest: String): List<String> = listOf("app", "policy") + rest + listOf("--dir", unit?.projectDir ?: ".")

	private fun loadPolicy() {
		val u = unit ?: return
		ADMCli.async(project, policyArgs("get", "--json"), u.projectDir) { r ->
			if (unit !== u) return@async
			val o = r.json().asObjectOrNull()
			if (o == null) {
				policy = null
				policyPath.text = r.failure()
				policyInit.isVisible = false
			} else {
				val p = Policy(o.str("path") ?: "", o.bool("exists"), o.arr("rules").objects().map { x ->
					Rule(x.str("subject") ?: "*", x.str("permission") ?: "", x.str("field") ?: "", x.str("kind") ?: "", x.arr("values").strings())
				})
				policy = p
				policyPath.text = if (p.exists) p.path else "No policy.toml at ${p.path}"
				policyInit.isVisible = !p.exists
			}
			showRules()
		}
	}

	private fun showRules() {
		val all = policy?.rules.orEmpty()
		val shown = rulesFilter?.let { f -> all.filter { it.permission == f } } ?: all
		// Every permission the audit knows gets a "whole permission" row first
		// (field *), so a permission without fields is never left without a
		// rule to fill in.
		val permissions = audited.flatMap { it.requirements }.map { it.permission }.distinct().sorted()
			.filter { rulesFilter == null || it == rulesFilter }
		val placeholders = if (policy?.exists == true) permissions
			.filter { p -> all.none { it.permission == p && it.field == "*" && it.subject == "*" } }
			.map { p -> Rule("*", p, "*", "ask", emptyList(), placeholder = true) } else emptyList()
		rules = placeholders + shown
		rulesModel.rowCount = 0
		for (r in rules) rulesModel.addRow(arrayOf(r.subject, r.permission, r.key, r.values.joinToString(", ")))
		rulesTable.emptyText.text = when {
			policy?.exists != true -> "No policy.toml"
			rulesFilter != null -> "No rules for $rulesFilter"
			else -> "No rules"
		}
	}

	private fun initPolicy() {
		val u = unit ?: return
		ADMCli.async(project, policyArgs("init"), u.projectDir) { r ->
			if (!r.ok) notify("adm app policy init failed: ${r.failure()}", NotificationType.ERROR)
			loadPolicy()
		}
	}

	private fun commitValues(row: Int) {
		val r = rules.getOrNull(row) ?: return
		val text = rulesModel.getValueAt(row, 3)?.toString()?.trim().orEmpty()
		if (r.placeholder) {
			// The row becomes a rule once it has a kind and a value; nothing is written before.
			val key = rulesModel.getValueAt(row, 2)?.toString()?.trim().orEmpty()
			if (text.isEmpty()) return
			if (key !in WHOLE_PERMISSION_KEYS) return notify("A whole-permission rule is *.allow, *.deny or *.ask", NotificationType.WARNING)
			setRule(r.subject, r.permission, key, text)
			return
		}
		if (text == r.values.joinToString(", ")) return
		if (text.isEmpty()) return notify("A rule needs at least one value; * matches everything the permission covers. Use the - button to remove the rule.", NotificationType.WARNING)
		setRule(r.subject, r.permission, r.key, text)
	}

	private fun setRule(subject: String, permission: String, key: String, values: String, then: () -> Unit = { loadPolicy() }) {
		val u = unit ?: return
		val args = arrayListOf("set")
		if (subject.isNotEmpty() && subject != "*") args += listOf("--package", subject)
		args += listOf(permission, key, values)
		ADMCli.async(project, policyArgs(*args.toTypedArray()), u.projectDir) { r ->
			if (!r.ok) notify("adm app policy set failed: ${r.failure()}", NotificationType.ERROR)
			then()
		}
	}

	private fun unsetRule(r: Rule, then: () -> Unit) {
		val u = unit ?: return
		val args = arrayListOf("unset")
		if (r.subject.isNotEmpty() && r.subject != "*") args += listOf("--package", r.subject)
		args += listOf(r.permission, r.key)
		ADMCli.async(project, policyArgs(*args.toTypedArray()), u.projectDir) { res ->
			if (!res.ok) notify("adm app policy unset failed: ${res.failure()}", NotificationType.ERROR)
			then()
		}
	}

	/** A TOML array literal for `adm app policy set`, so a value holding a comma survives. */
	private fun tomlList(values: List<String>): String =
		values.joinToString(", ", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }

	private fun removeRule() {
		val r = rules.getOrNull(rulesTable.selectedRow) ?: return
		if (r.placeholder) return
		unsetRule(r) { loadPolicy() }
	}

	/**
	 * The add/edit-rule form: subject, permission, field and kind from the
	 * audit, values as a list edited with + and -. Editing a rule whose
	 * subject, permission or key changed removes the old line first.
	 */
	private fun ruleDialog(existing: Rule?) {
		if (unit?.kind != ADMUnit.Kind.Application) return
		val permissions = audited.flatMap { it.requirements }.map { it.permission }.distinct().sorted()
		val packages = listOf("*") + audited.map { it.pkg }.distinct()
		val subject = ComboBox(packages.toTypedArray()).apply { isEditable = true }
		val permission = ComboBox(permissions.toTypedArray()).apply { isEditable = true; rulesFilter?.let { selectedItem = it } }
		val field = ComboBox<String>().apply { isEditable = true }
		val kind = ComboBox(arrayOf("allow", "deny", "ask", "setting"))
		val valuesModel = object : DefaultTableModel(arrayOf("Value"), 0) {
			override fun isCellEditable(row: Int, column: Int) = true
		}
		val valuesTable = JBTable(valuesModel).apply {
			setShowGrid(false)
			tableHeader = null
			selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
			emptyText.text = "No values"
		}
		val valuesPane = ToolbarDecorator.createDecorator(valuesTable)
			.setAddAction {
				if (valuesTable.isEditing) valuesTable.cellEditor.stopCellEditing()
				valuesModel.addRow(arrayOf(""))
				val row = valuesModel.rowCount - 1
				valuesTable.setRowSelectionInterval(row, row)
				valuesTable.editCellAt(row, 0)
				valuesTable.editorComponent?.requestFocusInWindow()
			}
			.setRemoveAction {
				if (valuesTable.isEditing) valuesTable.cellEditor.cancelCellEditing()
				val row = valuesTable.selectedRow
				if (row >= 0) valuesModel.removeRow(row)
			}
			.disableUpDownActions()
			.setPreferredSize(java.awt.Dimension(JBUI.scale(520), JBUI.scale(140)))
			.createPanel()
		val settingValue = JBTextField()
		// A setting is one value: its row shows and the list's row hides, so
		// the dialog shrinks to the text field rather than keeping the list's height.
		var listRow: com.intellij.ui.dsl.builder.Row? = null
		var settingRow: com.intellij.ui.dsl.builder.Row? = null
		fun showValuesFor(chosenKind: String) {
			val setting = chosenKind == "setting"
			listRow?.visible(!setting)
			settingRow?.visible(setting)
		}
		fun refillFields() {
			val chosen = permission.editor.item?.toString().orEmpty()
			val fields = audited.flatMap { it.requirements }.filter { it.permission == chosen }.flatMap { it.fields }.distinct()
			val keep = field.editor.item?.toString().orEmpty()
			field.removeAllItems()
			field.addItem("*")
			for (f in fields) field.addItem(f)
			if (keep.isNotEmpty()) field.selectedItem = keep
		}
		if (existing != null) {
			subject.selectedItem = existing.subject
			permission.selectedItem = existing.permission
			kind.selectedItem = if (existing.kind.isEmpty()) "setting" else existing.kind
			if (existing.kind.isEmpty()) settingValue.text = existing.values.firstOrNull().orEmpty()
			else for (v in existing.values) valuesModel.addRow(arrayOf(v))
		}
		refillFields()
		if (existing != null) field.selectedItem = existing.field
		permission.addActionListener { refillFields() }
		kind.addActionListener {
			showValuesFor(kind.selectedItem?.toString().orEmpty())
			javax.swing.SwingUtilities.getWindowAncestor(kind)?.pack()
		}
		val dialog = object : DialogWrapper(project, true) {
			init {
				title = if (existing == null) "Add Policy Rule" else "Edit Policy Rule"
				init()
			}

			override fun createCenterPanel(): JComponent = panel {
				row("Subject:") { cell(subject).align(Align.FILL) }.rowComment("* is the whole program; the application's name or a dependency's namespace:name narrows one package")
				row("Permission:") { cell(permission).align(Align.FILL) }
				row("Field:") { cell(field).align(Align.FILL) }.rowComment("For allow, deny or ask: the field the list applies to, e.g. path or host, or * for the whole permission. $WHOLE_PERMISSION_HINT For a setting: its name, e.g. maxConnections")
				row("Kind:") { cell(kind) }.rowComment("allow, deny and ask are lists the field is matched against; a setting is one key = value line, like maxConnections = 20")
				listRow = row("Values:") { cell(valuesPane).align(Align.FILL) }.rowComment("One glob per row: paths like \${app.home}/**, hosts like *.example.net, networks like 10.0.0.0/8")
				settingRow = row("Value:") { cell(settingValue).align(Align.FILL) }.rowComment("One value, like 20")
				showValuesFor(kind.selectedItem?.toString().orEmpty())
			}

			override fun doOKAction() {
				if (valuesTable.isEditing) valuesTable.cellEditor.stopCellEditing()
				super.doOKAction()
			}
		}
		if (!dialog.showAndGet()) return
		val f = field.editor.item?.toString()?.trim().orEmpty()
		val k = kind.selectedItem?.toString().orEmpty()
		val key = if (k == "setting") f else "$f.$k"
		val perm = permission.editor.item?.toString()?.trim().orEmpty()
		val subj = subject.editor.item?.toString()?.trim().orEmpty().ifEmpty { "*" }
		if (perm.isEmpty()) return notify("Choose the permission the rule is for", NotificationType.WARNING)
		if (f.isEmpty()) return notify(if (k == "setting") "Give the setting a name, e.g. maxConnections" else "Choose a field, or * for the whole permission", NotificationType.WARNING)
		val values = (0 until valuesModel.rowCount).map { valuesModel.getValueAt(it, 0)?.toString()?.trim().orEmpty() }.filter { it.isNotEmpty() }
		if (k != "setting" && values.isEmpty()) return notify("Add at least one value; * matches everything the permission covers", NotificationType.WARNING)
		val literal = if (k == "setting") settingValue.text.trim() else tomlList(values)
		if (literal.isEmpty()) return notify("A setting needs a value, e.g. 20", NotificationType.WARNING)
		val moved = existing != null && (existing.subject != subj || existing.permission != perm || existing.key != key)
		if (moved) unsetRule(existing!!) { setRule(subj, perm, key, literal) } else setRule(subj, perm, key, literal)
	}

	// --- decision log ------------------------------------------------------------

	private fun shouldTail(): Boolean {
		val u = unit ?: return false
		return live && u.kind == ADMUnit.Kind.Application && services.isRunning(u.name)
	}

	private fun loadLog(quiet: Boolean) {
		val u = unit ?: return
		if (u.kind != ADMUnit.Kind.Application) return
		if (!quiet) logStatus.text = "Reading…"
		ADMCli.async(project, policyArgs("log"), u.projectDir) { r ->
			if (unit !== u) return@async
			if (!r.ok) {
				logStatus.text = r.failure()
				return@async
			}
			// Newest first: the file appends, the table shows the latest on top.
			val parsed = r.stdout.lineSequence().mapNotNull { parseDecision(it) }.toList().asReversed()
			val changed = parsed.size != decisions.size || parsed.firstOrNull()?.time != decisions.firstOrNull()?.time
			decisions = parsed
			val tail = if (shouldTail()) "  ·  tailing while ${u.name} runs" else ""
			logStatus.text = when {
				parsed.isEmpty() && r.stdout.startsWith("no decisions logged") -> r.stdout.trim()
				else -> "${parsed.size} decision(s)$tail"
			}
			if (changed) {
				showLog()
				// A new decision may have remembered an "Always" answer as a
				// rule in policy.toml; the rules table has to show it.
				loadPolicy()
			}
		}
	}

	private fun clearLog() {
		val u = unit ?: return
		ADMCli.async(project, policyArgs("log", "--clear"), u.projectDir) { r ->
			if (!r.ok) notify("adm app policy log --clear failed: ${r.failure()}", NotificationType.ERROR)
			loadLog(quiet = false)
		}
	}

	/** `2026-09-05T14:05:09Z deny adm.network.connect subject=acme:imaging field=host host.deny matched *.example.net`. */
	private fun parseDecision(line: String): Decision? {
		val at = line.indexOf(" subject=")
		if (at < 0) return null
		val head = line.substring(0, at).trim().split(' ').filter { it.isNotEmpty() }
		if (head.size < 3) return null
		val permission = head.last()
		val outcome = head[head.size - 2]
		val time = displayTime(head.dropLast(2).joinToString(" "))
		var rest = line.substring(at + " subject=".length)
		val subject = rest.substringBefore(' ')
		rest = rest.substringAfter(' ', "")
		var field = ""
		if (rest.startsWith("field=")) {
			field = rest.removePrefix("field=").substringBefore(' ')
			rest = rest.substringAfter(' ', "")
		}
		return Decision(time, outcome, permission, subject, field, rest.trim())
	}

	/** `2026-09-05T14:05:09.123456Z` as `2026-09-05 14:05:09.123456`: the fraction stays, the T and Z go. */
	private fun displayTime(time: String): String {
		val t = time.trim()
		if (t.length < 11 || t[10] != 'T') return t
		return t.substring(0, 10) + " " + t.substring(11).removeSuffix("Z")
	}

	private fun showLog() {
		val atTop = logTable.selectedRow <= 0
		logModel.rowCount = 0
		for (d in decisions) {
			if (!showIgnored && d.outcome == "ignore") continue
			logModel.addRow(arrayOf(d.time, d.outcome, d.permission, d.subject, d.field, d.reason))
		}
		if (atTop && logModel.rowCount > 0) {
			logTable.setRowSelectionInterval(0, 0)
			logTable.scrollRectToVisible(logTable.getCellRect(0, 0, true))
		}
	}

	/**
	 * The log carries no source location, so a decision leads to its
	 * permission in the Permissions section, where the call chains are.
	 */
	private fun revealDecision(row: Int) {
		val d = decisions.getOrNull(row) ?: return
		sections.select("Permissions")
		for (i in 0 until treeRoot.childCount) {
			val n = treeRoot.getChildAt(i) as DefaultMutableTreeNode
			if ((n.userObject as? PermissionNode)?.permission == d.permission) {
				TreeUtil.selectNode(tree, n)
				tree.expandPath(javax.swing.tree.TreePath(n.path))
				return
			}
		}
		notify("${d.permission}: not among the audited permissions", NotificationType.INFORMATION)
	}

	private fun notify(text: String, type: NotificationType) {
		NotificationGroupManager.getInstance().getNotificationGroup("ADM").createNotification(text, type).notify(project)
	}

	override fun dispose() {
		poll.stop()
	}
}
