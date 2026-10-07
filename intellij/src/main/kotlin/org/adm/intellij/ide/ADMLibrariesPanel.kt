package org.adm.intellij.ide

import com.google.gson.JsonArray
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
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.adm.intellij.ADMIcons
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The Libraries tab: the package manager for the selected project. A rail of
 * two icon tabs on the left switches the list between what the registries
 * returned for the search and what `adm.lock` names; the right side describes
 * the selected library and lets the user grant or withdraw its permissions,
 * set rules for them, update, remove or install it. Every operation is an adm
 * command: `list packages`, `search`, `get`, `lib remove`, `app policy`.
 */
class ADMLibrariesPanel(private val project: Project, parentDisposable: Disposable) : JBPanelWithEmptyText(BorderLayout()), Disposable {
	/** One row of the list: an installed package or a search hit. */
	private class Library(
		val library: String,
		val namespace: String,
		val name: String,
		val version: String?,
		val latest: String?,
		val update: Boolean,
		val installed: Boolean,
		val state: String?,
		val description: String?,
		val repo: String?,
		val publisher: String?,
		val registry: String?,
		val permissions: List<String>?,
		val versions: List<String>,
		val hit: Boolean,
		/** What the package's code reaches; null until known (a search hit before its dry run). */
		val requirements: List<Permission>?,
		/** Where the newest version works (`compat` of the search hit); null when unknown, "All" when unconstrained. */
		val compat: String? = null,
		/** Every registry carrying some version of a search hit. */
		val registries: List<String> = emptyList(),
	)

	/** One permission a library's code reaches (`requirements[]` of list packages / get --dry-run). */
	private class Permission(val name: String, val description: String?, val via: List<String>, val spec: String?, val fields: List<String>)

	/** One rule of the application's policy file: `[subject.permission] field.kind = [values]`. */
	private class Rule(val subject: String, val permission: String, val field: String, val kind: String, val values: List<String>)

	private class Index(val name: String, val url: String, val official: Boolean) {
		override fun toString(): String = if (official) "Official repository" else name
	}

	private enum class View(val title: String) { Results("Results"), Installed("Installed") }

	private val search = SearchTextField(true)
	private val indexCombo = ComboBox<Index>()
	private val listModel = DefaultListModel<Library>()
	private val list = JBList(listModel)
	private val listHost = JBPanelWithEmptyText(BorderLayout())
	private val listTitle = JBLabel(View.Installed.title)
	private val detail = JBPanelWithEmptyText(BorderLayout())
	private val permissionModel = CheckBoxList<String>()
	private val permissionPage = JBPanelWithEmptyText(BorderLayout())
	private var permissions: List<Permission> = emptyList()
	private var rules: List<Rule> = emptyList()
	private var unit: ADMUnit? = null
	private var view = View.Installed
	private var installed: List<Library> = emptyList()
	private var results: List<Library> = emptyList()
	private var resultsFor: String = ""
	/** A pull of the registries is in flight; the Refresh button spins meanwhile. */
	private var refreshing = false

	init {
		Disposer.register(parentDisposable, this)

		// Header: search across the registries, the index selector (only when
		// the project names a custom one) and a refresh that pulls them.
		search.textEditor.emptyText.text = "Search libraries"
		search.textEditor.addKeyListener(object : KeyAdapter() {
			override fun keyPressed(e: KeyEvent) {
				if (e.keyCode == KeyEvent.VK_ENTER) runSearch()
				if (e.keyCode == KeyEvent.VK_ESCAPE) {
					search.text = ""
					show(View.Installed)
				}
			}
		})
		// Emptying the field drops the results with it.
		search.textEditor.document.addDocumentListener(object : DocumentListener {
			override fun insertUpdate(e: DocumentEvent) = cleared()
			override fun removeUpdate(e: DocumentEvent) = cleared()
			override fun changedUpdate(e: DocumentEvent) = cleared()
			private fun cleared() {
				if (search.text.isNotBlank() || resultsFor.isEmpty()) return
				results = emptyList()
				resultsFor = ""
				if (view == View.Results) show(View.Results)
			}
		})
		indexCombo.isVisible = false
		val refresh = object : DumbAwareAction("Refresh", "Pull every registry index and list the packages again", AllIcons.Actions.Refresh) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.icon = if (refreshing) com.intellij.ui.AnimatedIcon.Default.INSTANCE else AllIcons.Actions.Refresh
				e.presentation.isEnabled = !refreshing && unit != null
			}
			override fun actionPerformed(e: AnActionEvent) = reload(pull = true)
		}
		val toolbar = ActionManager.getInstance().createActionToolbar("ADMLibraries", DefaultActionGroup(refresh), true)
		toolbar.targetComponent = this
		val header = JPanel(BorderLayout()).apply {
			border = JBUI.Borders.empty(4, 6, 4, 2)
			add(search, BorderLayout.CENTER)
			add(JPanel(BorderLayout()).apply {
				add(indexCombo, BorderLayout.WEST)
				add(toolbar.component, BorderLayout.EAST)
			}, BorderLayout.EAST)
		}

		// The rail: one icon tab per view, like the Docker window's left column.
		val rail = ActionManager.getInstance().createActionToolbar(
			"ADMLibrariesRail",
			DefaultActionGroup(ViewAction(View.Results, AllIcons.Actions.Search), ViewAction(View.Installed, AllIcons.Nodes.PpLib)),
			false,
		)
		rail.targetComponent = this
		rail.component.border = JBUI.Borders.customLineRight(JBUI.CurrentTheme.ToolWindow.borderColor())

		list.selectionMode = ListSelectionModel.SINGLE_SELECTION
		list.cellRenderer = object : ColoredListCellRenderer<Library>() {
			override fun customizeCellRenderer(l: JList<out Library>, value: Library?, index: Int, selected: Boolean, hasFocus: Boolean) {
				value ?: return
				ipad = JBUI.insets(3, 6)
				icon = ADMIcons.LIBRARY
				append(value.library)
				if (value.update) {
					append("  ")
					append("↑", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.namedColor("Link.activeForeground", JBColor.BLUE)))
				}
				if (value.state == "missing") append("  missing", SimpleTextAttributes.ERROR_ATTRIBUTES)
				if (value.hit && installedRow(value) != null) append("  installed", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
				val right = value.version ?: value.latest ?: ""
				if (right.isNotEmpty()) {
					append("  ")
					append(right, SimpleTextAttributes.GRAYED_ATTRIBUTES)
				}
			}
		}
		list.addListSelectionListener { if (!it.valueIsAdjusting) showDetail(list.selectedValue) }
		listTitle.border = JBUI.Borders.empty(6, 8, 4, 8)
		listTitle.font = JBUI.Fonts.label().asBold()
		listHost.add(listTitle, BorderLayout.NORTH)
		listHost.add(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
		val left = JPanel(BorderLayout()).apply {
			add(rail.component, BorderLayout.WEST)
			add(listHost, BorderLayout.CENTER)
		}

		detail.emptyText.text = "Select a library"
		permissionModel.setCheckBoxListListener { _, _ -> }
		permissionModel.addListSelectionListener { if (!it.valueIsAdjusting) showPermission() }
		permissionPage.emptyText.text = "Select a permission"

		val splitter = OnePixelSplitter(false, 0.25f)
		splitter.splitterProportionKey = "ADM.Libraries.split"
		splitter.firstComponent = left
		splitter.secondComponent = detail

		add(header, BorderLayout.NORTH)
		add(splitter, BorderLayout.CENTER)
		emptyText.text = "Select a project to manage its libraries"

		ADMWorkspace.getInstance(project).addListener(object : ADMWorkspace.Listener {
			override fun selectionChanged(unit: ADMUnit?) = bind(unit)
		}, this)
		bind(ADMWorkspace.getInstance(project).selected)
	}

	/** A rail tab. */
	private inner class ViewAction(private val target: View, icon: javax.swing.Icon) : ToggleAction(target.title, target.title, icon), DumbAware {
		override fun getActionUpdateThread() = ActionUpdateThread.EDT
		override fun isSelected(e: AnActionEvent): Boolean = view == target
		override fun setSelected(e: AnActionEvent, state: Boolean) {
			if (state) show(target)
		}
	}

	private fun bind(unit: ADMUnit?) {
		if (unit?.projectDir == this.unit?.projectDir && unit != null) return
		this.unit = unit
		installed = emptyList()
		results = emptyList()
		listModel.clear()
		showDetail(null)
		val visible = unit != null
		for (c in components) c.isVisible = visible
		revalidate()
		repaint()
		if (visible) reload(pull = false)
	}

	/** Lists the project's packages and its registries. */
	private fun reload(pull: Boolean) {
		val dir = unit?.projectDir ?: return
		if (pull) {
			refreshing = true
			ADMCli.async(project, listOf("search", "--refresh", "--indexes", "--json", "--dir", dir), dir) { r ->
				showIndexes(r)
				listPackages(dir, emptyList())
			}
		} else {
			ADMCli.async(project, listOf("search", "--indexes", "--json", "--dir", dir), dir) { r -> showIndexes(r) }
			listPackages(dir)
		}
	}

	private fun listPackages(dir: String, flags: List<String> = emptyList()) {
		ADMCli.async(project, listOf("list", "packages", "--json", "--dir", dir) + flags, dir) { r ->
			refreshing = false
			if (unit?.projectDir != dir) return@async
			if (!r.ok) {
				installed = emptyList()
				if (view == View.Installed) fill(emptyList(), r.failure())
				return@async
			}
			installed = (r.json() as? JsonArray)?.mapNotNull { parseInstalled(it as? JsonObject ?: return@mapNotNull null) }.orEmpty()
			if (view == View.Installed) show(View.Installed) else list.repaint()
		}
	}

	private fun showIndexes(r: ADMCli.Result) {
		val items = (r.json() as? JsonArray)?.mapNotNull { el ->
			val o = el as? JsonObject ?: return@mapNotNull null
			Index(o.str("name") ?: return@mapNotNull null, o.str("url") ?: "", o.get("official")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
		}.orEmpty()
		indexCombo.removeAllItems()
		for (i in items) indexCombo.addItem(i)
		indexCombo.isVisible = items.size > 1
	}

	private fun runSearch() {
		val dir = unit?.projectDir ?: return
		val text = search.text.trim()
		if (text.isEmpty()) {
			show(View.Installed)
			return
		}
		search.addCurrentTextToHistory()
		resultsFor = text
		show(View.Results)
		listHost.emptyText.text = "Searching…"
		ADMCli.async(project, listOf("search", "--json", "--dir", dir, text), dir) { r ->
			if (resultsFor != text) return@async
			if (!r.ok) {
				results = emptyList()
				if (view == View.Results) fill(emptyList(), r.failure())
				return@async
			}
			val wanted = (indexCombo.selectedItem as? Index)?.takeIf { indexCombo.isVisible }?.name
			results = (r.json() as? JsonArray)?.mapNotNull { parseHit(it as? JsonObject ?: return@mapNotNull null) }
				.orEmpty().filter { wanted == null || wanted in it.registries || it.registry == wanted }
				.groupBy { it.library }.values.map(::merge)
			if (view == View.Results) show(View.Results)
		}
	}

	/**
	 * One row per library: the same library carried by several registries
	 * merges into one hit with every version, the newest as latest.
	 */
	private fun merge(rows: List<Library>): Library {
		val first = rows.first()
		if (rows.size == 1) return first
		val versions = rows.flatMap { it.versions }.plus(rows.mapNotNull { it.latest }).distinct().sortedWith(newestFirst)
		return Library(first.library, first.namespace, first.name, null, versions.firstOrNull() ?: first.latest, false, false, null,
			rows.firstNotNullOfOrNull { it.description }, rows.firstNotNullOfOrNull { it.repo }, rows.firstNotNullOfOrNull { it.publisher },
			rows.mapNotNull { it.registry }.distinct().joinToString(", ").ifEmpty { null }, null, versions, true, null, rows.firstNotNullOfOrNull { it.compat })
	}

	/** Orders version strings newest first, comparing dotted numbers numerically. */
	private val newestFirst = Comparator<String> { a, b ->
		val x = a.split('.', '-').map { it.toIntOrNull() ?: -1 }
		val y = b.split('.', '-').map { it.toIntOrNull() ?: -1 }
		for (i in 0 until maxOf(x.size, y.size)) {
			val c = (y.getOrNull(i) ?: 0).compareTo(x.getOrNull(i) ?: 0)
			if (c != 0) return@Comparator c
		}
		b.compareTo(a)
	}

	/** Switches the list to [target] and fills it from what that view holds. */
	private fun show(target: View) {
		view = target
		listTitle.text = if (target == View.Results && resultsFor.isNotEmpty()) "Results (${results.size})" else target.title
		when (target) {
			View.Installed -> fill(installed, "No libraries installed")
			View.Results -> fill(results, if (resultsFor.isEmpty()) "Type above and press Enter to search the registries" else "Nothing matches \"$resultsFor\"")
		}
	}

	private fun fill(rows: List<Library>, empty: String) {
		val previous = list.selectedValue?.library
		listModel.clear()
		rows.forEach(listModel::addElement)
		listHost.emptyText.text = empty
		if (rows.isEmpty() && view == View.Installed) listHost.emptyText.appendSecondaryText("Search above to find one", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
		val idx = rows.indexOfFirst { it.library == previous }
		if (idx >= 0) list.selectedIndex = idx else showDetail(null)
	}

	/** The installed row for a search hit of the same library, if any. */
	private fun installedRow(hit: Library): Library? = installed.firstOrNull { it.name == hit.name && (it.namespace == hit.namespace || it.namespace.isEmpty()) }

	private fun parseInstalled(o: JsonObject): Library? = o.str("library")?.let { library -> Library(
		library = library,
		namespace = o.str("namespace") ?: "",
		name = o.str("name") ?: "",
		version = o.str("version"),
		latest = o.str("latest"),
		update = o.get("update")?.takeIf { it.isJsonPrimitive }?.let { it.asString.isNotEmpty() && it.asString != "false" } ?: false,
		installed = o.get("installed")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
		state = o.str("state"),
		description = o.str("description"),
		repo = o.str("repo"),
		publisher = o.str("publisher"),
		registry = o.str("registry"),
		permissions = o.arr("permissions").strings(),
		versions = emptyList(),
		hit = false,
		requirements = o.get("requirements")?.takeIf { it.isJsonArray }?.asJsonArray?.let(::parseRequirements),
	) }

	private fun parseRequirements(arr: JsonArray): List<Permission> {
		val seen = LinkedHashMap<String, Permission>()
		for (el in arr) {
			val o = el as? JsonObject ?: continue
			val name = o.str("permission") ?: continue
			if (name in seen) continue
			seen[name] = Permission(
				name = name,
				description = o.str("description"),
				via = o.arr("via").strings(),
				spec = o.str("spec"),
				fields = o.arr("fields").strings(),
			)
		}
		return seen.values.toList()
	}

	private fun parseHit(o: JsonObject): Library? {
		val ns = o.str("namespace") ?: ""
		val name = o.str("name") ?: return null
		return Library(
			library = if (ns.isEmpty()) name else "$ns:$name",
			namespace = ns,
			name = name,
			version = null,
			latest = o.str("latest"),
			update = false,
			installed = false,
			state = null,
			description = o.str("description"),
			repo = o.str("repo"),
			publisher = o.str("publisher"),
			registry = o.str("registry"),
			permissions = null,
			versions = o.arr("versions").strings().sortedWith(newestFirst),
			hit = true,
			requirements = null,
			compat = compatSummary(o.obj("compat")),
			registries = o.arr("registries").strings(),
		)
	}

	/** `windows · arm64 · adm ≥ 0.1.1500`, or "All" when the hit constrains nothing. */
	private fun compatSummary(o: JsonObject?): String {
		o ?: return "All"
		val parts = ArrayList<String>()
		o.arr("os").strings().takeIf { it.isNotEmpty() }?.let { parts.add(it.joinToString(", ")) }
		o.arr("arch").strings().takeIf { it.isNotEmpty() }?.let { parts.add(it.joinToString(", ")) }
		o.str("adm")?.takeIf { it.isNotBlank() }?.let { parts.add("adm ≥ $it") }
		return if (parts.isEmpty()) "All" else parts.joinToString("  ·  ")
	}

	// --- detail -----------------------------------------------------------

	private fun showDetail(selected: Library?) {
		detail.removeAll()
		permissions = emptyList()
		rules = emptyList()
		permissionModel.clear()
		permissionPage.removeAll()
		if (selected == null) {
			detail.revalidate()
			detail.repaint()
			return
		}
		val dir = unit?.projectDir ?: return
		// A hit that is already installed is shown as the installed package: same buttons, same accepted set.
		val lib = if (selected.hit) installedRow(selected)?.let { row ->
			Library(row.library, row.namespace, row.name, row.version, row.latest ?: selected.latest, row.update, true, row.state,
				row.description ?: selected.description, row.repo ?: selected.repo, row.publisher ?: selected.publisher, row.registry ?: selected.registry,
				row.permissions, selected.versions, false, row.requirements, selected.compat)
		} ?: selected else selected

		val updateButton = JButton("Update to ${lib.latest}", AllIcons.Actions.Download).apply {
			isVisible = lib.update && !lib.hit
			addActionListener { install(lib, lib.latest, accepted()) }
		}
		// Install takes the version picked beside it; "Latest" leaves the choice to adm.
		val versionCombo = ComboBox<String>().apply {
			isVisible = lib.hit
			addItem(LATEST)
			for (v in lib.versions) addItem(v)
			toolTipText = "Version to install"
		}
		val installButton = JButton("Install", AllIcons.Actions.Install).apply {
			isVisible = lib.hit
			addActionListener { install(lib, (versionCombo.selectedItem as? String)?.takeIf { it != LATEST }, accepted()) }
		}
		val saveButton = JButton("Save permissions", AllIcons.Actions.MenuSaveall).apply {
			isVisible = !lib.hit
			addActionListener { install(lib, lib.version, accepted()) }
		}
		val removeButton = JButton("Remove", AllIcons.Actions.GC).apply {
			isVisible = !lib.hit
			addActionListener { remove(lib) }
		}

		val description = JBLabel().apply {
			text = "<html>${(lib.description?.takeIf { it.isNotBlank() } ?: "No description").replace("<", "&lt;")}</html>"
			font = JBUI.Fonts.label(13f)
			foreground = UIUtil.getLabelForeground()
		}
		val info = panel {
			row {
				label(lib.library).applyToComponent { font = JBUI.Fonts.label(16f).asBold() }
			}
			row { cell(description).align(Align.FILL) }.bottomGap(com.intellij.ui.dsl.builder.BottomGap.SMALL)
			row("Version:") {
				label(listOfNotNull(lib.version ?: lib.latest, lib.latest?.takeIf { it != lib.version && lib.version != null }?.let { "latest $it" }).joinToString("  ·  ").ifEmpty { "-" })
			}
			lib.compat?.let { row("Compatibility:") { label(it) } }
			lib.publisher?.takeIf { it.isNotBlank() }?.let { row("Publisher:") { label(it) } }
			lib.repo?.takeIf { it.isNotBlank() }?.let { url -> row("Repository:") { browserLink(url, url) } }
			lib.registry?.let { row("Registry:") { label(it) } }
			if (lib.versions.isNotEmpty()) row("Versions:") { label(lib.versions.take(8).joinToString(", ")) }
			row {
				cell(installButton)
				cell(versionCombo)
				cell(updateButton)
				cell(saveButton)
				cell(removeButton)
			}
		}
		info.border = JBUI.Borders.empty(10, 16, 4, 16)

		val permissionSplit = OnePixelSplitter(false, 0.4f)
		val listPane = JPanel(BorderLayout()).apply {
			add(JBLabel("Permissions").apply { border = JBUI.Borders.empty(6, 8, 4, 8); font = JBUI.Fonts.label().asBold() }, BorderLayout.NORTH)
			add(JBScrollPane(permissionModel).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
		}
		permissionSplit.firstComponent = listPane
		permissionSplit.secondComponent = permissionPage
		permissionPage.emptyText.text = "Select a permission"

		val body = JPanel(BorderLayout())
		body.add(info, BorderLayout.NORTH)
		body.add(permissionSplit, BorderLayout.CENTER)
		detail.add(body, BorderLayout.CENTER)
		detail.revalidate()
		detail.repaint()

		loadPermissions(lib, dir)
	}

	/** Fills the checklist from what the package declares; a search hit is fetched first (`adm get --dry-run`). */
	private fun loadPermissions(lib: Library, dir: String) {
		val known = lib.requirements
		if (known != null) {
			fillPermissions(lib, known)
			loadRules(lib, dir)
			return
		}
		permissionPage.emptyText.text = "Fetching ${lib.library}…"
		ADMCli.async(project, listOf("get", lib.library, "--dry-run", "--json", "--no-pull", "--dir", dir), dir) { r ->
			if (list.selectedValue?.library != lib.library) return@async
			val first = (r.json() as? JsonArray)?.firstOrNull() as? JsonObject
			val reqs = first?.get("requirements")?.takeIf { it.isJsonArray }?.asJsonArray?.let(::parseRequirements)
			if (reqs == null) {
				permissionPage.emptyText.text = r.failure()
				return@async
			}
			fillPermissions(lib, reqs)
			loadRules(lib, dir)
		}
	}

	private fun fillPermissions(lib: Library, reqs: List<Permission>) {
		val seen = LinkedHashMap<String, Permission>()
		for (p in reqs) seen[p.name] = p
		// Permissions the lock accepted but the package no longer lists stay visible, so they can be withdrawn.
		for (p in lib.permissions.orEmpty()) if (p !in seen) seen[p] = Permission(p, null, emptyList(), null, emptyList())
		permissions = seen.values.toList()
		permissionModel.clear()
		// A search hit starts with everything ticked: the checklist is the consent form before Install.
		val accepted = if (lib.hit) permissions.map { it.name }.toSet() else lib.permissions.orEmpty().toSet()
		for (p in permissions) permissionModel.addItem(p.name, p.name, p.name in accepted)
		permissionPage.emptyText.text = if (permissions.isEmpty()) "This library needs no permissions" else "Select a permission"
	}

	private fun accepted(): List<String> = permissions.map { it.name }.filter { permissionModel.isItemSelected(it) }

	// --- rules ------------------------------------------------------------

	/** Reads the application's policy file; only an application has one. */
	private fun loadRules(lib: Library, dir: String) {
		rules = emptyList()
		val app = unit?.takeIf { it.kind == ADMUnit.Kind.Application } ?: return
		ADMCli.async(project, listOf("app", "policy", "get", "--json", "--dir", dir, "--app", app.name), dir) { r ->
			if (list.selectedValue?.library != lib.library) return@async
			val arr = r.json().asObjectOrNull()?.arr("rules") ?: return@async
			rules = arr.mapNotNull { el ->
				val o = el as? JsonObject ?: return@mapNotNull null
				Rule(
					subject = o.str("subject") ?: "*",
					permission = o.str("permission") ?: return@mapNotNull null,
					field = o.str("field") ?: "",
					kind = o.str("kind") ?: "",
					values = o.arr("values").strings(),
				)
			}
			showPermission()
		}
	}

	private fun showPermission() {
		permissionPage.removeAll()
		val name = permissionModel.selectedValue as? String
		val p = permissions.firstOrNull { it.name == name }
		val lib = list.selectedValue
		val app = unit?.takeIf { it.kind == ADMUnit.Kind.Application }
		if (p != null && lib != null) {
			val edits = LinkedHashMap<Pair<String, String>, JTextField>()
			fun current(field: String, kind: String): String =
				rules.firstOrNull { it.subject == lib.library && it.permission == p.name && it.field == field && it.kind == kind }
					?.values?.joinToString(", ") ?: ""
			val page = panel {
				row { label(p.name).bold() }
				row { comment(p.description?.takeIf { it.isNotBlank() } ?: "No description") }
				if (p.via.isNotEmpty()) row("Reached through:") { label(p.via.joinToString(" → ")) }
				p.spec?.takeIf { it.isNotBlank() }?.let { row("Constraint:") { label(it) } }
				if (p.fields.isNotEmpty() && app != null) {
					group("Rules for ${lib.library}") {
						for (field in p.fields) {
							for (kind in listOf("allow", "deny", "ask")) {
								row("$field.$kind:") {
									val tf = JBTextField(current(field, kind))
									tf.emptyText.text = "comma-separated"
									edits[field to kind] = tf
									cell(tf).align(Align.FILL)
								}
							}
						}
						row {
							button("Save rules") { saveRules(lib, p, edits) }
						}.rowComment("Written to the application's policy.toml, the file the running program reads; an empty value removes the rule.")
					}
				} else if (p.fields.isNotEmpty()) {
					row { comment("Rules are set per application: select the application that uses this library to edit them.") }
				}
			}
			page.border = JBUI.Borders.empty(8, 12)
			permissionPage.add(JBScrollPane(page).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
		}
		permissionPage.revalidate()
		permissionPage.repaint()
	}

	private fun saveRules(lib: Library, p: Permission, edits: Map<Pair<String, String>, JTextField>) {
		val app = unit?.takeIf { it.kind == ADMUnit.Kind.Application } ?: return
		val dir = app.projectDir
		val steps = ArrayList<List<String>>()
		for ((key, tf) in edits) {
			val (field, kind) = key
			val text = tf.text.trim()
			val before = rules.firstOrNull { it.subject == lib.library && it.permission == p.name && it.field == field && it.kind == kind }?.values?.joinToString(", ") ?: ""
			if (text == before) continue
			val common = listOf("app", "policy", if (text.isEmpty()) "unset" else "set", "--dir", dir, "--app", app.name, "--package", lib.library, p.name, "$field.$kind")
			steps.add(if (text.isEmpty()) common else common + text)
		}
		if (steps.isEmpty()) return
		ApplicationManager.getApplication().executeOnPooledThread {
			var failure: String? = null
			for (args in steps) {
				val r = ADMCli.run(project, args, dir)
				if (!r.ok) {
					failure = "adm app policy ${args[2]}: ${r.failure()}"
					break
				}
			}
			ApplicationManager.getApplication().invokeLater({
				if (failure != null) notify(failure, NotificationType.ERROR) else loadRules(lib, dir)
			}, project.disposed)
		}
	}

	// --- operations -------------------------------------------------------

	private fun install(lib: Library, version: String?, accept: List<String>) {
		val dir = unit?.projectDir ?: return
		val spec = if (version != null) "${lib.library}@$version" else lib.library
		val acceptArg = if (accept.isEmpty()) "none" else accept.joinToString(",")
		ADMCli.background(project, "Installing ${lib.library}", listOf("get", spec, "--dir", dir, "--accept", acceptArg, "--no-pull"), dir) { r ->
			if (r.ok) {
				notify("${lib.library} installed", NotificationType.INFORMATION)
				listPackages(dir)
				show(View.Installed)
			} else {
				notify("adm get failed: ${r.failure()}", NotificationType.ERROR)
			}
		}
	}

	private fun remove(lib: Library) {
		val dir = unit?.projectDir ?: return
		val answer = Messages.showOkCancelDialog(project, "Remove ${lib.library} from adm.toml and adm.lock?", "Remove Library", "Remove", Messages.getCancelButton(), Messages.getQuestionIcon())
		if (answer != Messages.OK) return
		ADMCli.background(project, "Removing ${lib.library}", listOf("lib", "remove", lib.library, "--dir", dir), dir) { r ->
			if (r.ok) {
				listPackages(dir)
				show(View.Installed)
			} else notify("adm lib remove failed: ${r.failure()}", NotificationType.ERROR)
		}
	}

	private fun notify(text: String, type: NotificationType) {
		NotificationGroupManager.getInstance().getNotificationGroup("ADM").createNotification(text, type).notify(project)
	}


	override fun dispose() {}

	private companion object {
		/** The version dropdown's first entry: whatever adm resolves as newest. */
		const val LATEST = "Latest"
	}
}
