package org.adm.intellij.ide

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.ui.ColorUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.adm.intellij.ADMIcons
import org.adm.intellij.documentation.ADMDocComments
import org.adm.intellij.highlighting.ADMSyntaxHighlighter
import org.adm.intellij.lang.ADMLexer
import org.adm.intellij.lang.ADMTokenTypes
import java.awt.BorderLayout
import java.awt.Font
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The Documentation tab: every module the selected project can see (its own,
 * its libraries', the standard library) as a tree of modules and exports,
 * with a page for the selection on the right that looks like the editor's
 * hover popup: coloured declarations in grey boxes, the Markdown doc, and the
 * members of a type grouped as constructors, destructor, methods, infix,
 * properties, values. Type names in declarations link to their own page.
 * Overloads of one name share a tree node and a page; a page ends with the
 * Examples lifted out of its doc comments and its members'. The toolbar keeps
 * a back/forward history of the pages visited. Built from
 * `adm doc --workspace --json --full-doc`.
 */
class ADMDocumentationPanel(private val project: Project, parentDisposable: Disposable) : JBPanelWithEmptyText(BorderLayout()), Disposable {
	private class Example(val title: String?, val code: String)
	private class Module(val name: String, val internal: Boolean, val doc: String?, val examples: List<Example>, val tags: List<ADMDocComments.Tag>, val exports: List<Symbol>) {
		/** The exports by name, in source order: overloads of one name make one entry. */
		val entries: List<Entry> = exports.groupBy { it.name }.map { (name, forms) -> Entry(name, forms) }
	}
	private class Ref(val module: String, val name: String)
	private class Symbol(
		val name: String,
		val kind: Int,
		val kindName: String,
		val signature: String?,
		val doc: String?,
		val file: String?,
		val line: Int,
		val column: Int,
		val type: String?,
		val value: String?,
		/** A component's `@input` member: passed unnamed (`value`), assignable by the component (`bind`). */
		val valueInput: Boolean,
		val bindInput: Boolean,
		/** A component's `@state` field: others hear it change. */
		val state: Boolean,
		/** A component declared `@scoped`: in the tree for what it does, neither laid out nor drawn. */
		val scoped: Boolean,
		val implements: List<Ref>,
		val implementedBy: List<Ref>,
		val examples: List<Example>,
		val tags: List<ADMDocComments.Tag>,
		val members: List<Symbol>,
		val module: String,
	) {
		val isTypeLike: Boolean get() = kindName in TYPE_KINDS
	}
	/** One tree node: every export of the module with this name. */
	private class Entry(val name: String, val forms: List<Symbol>) {
		val first: Symbol get() = forms.first()
	}

	private val root = DefaultMutableTreeNode()
	private val model = DefaultTreeModel(root)
	private val tree = Tree(model)
	private val search = SearchTextField(false)
	private val pane: JBHtmlPane = JBHtmlPane(
		JBHtmlPaneStyleConfiguration.Builder().enableCodeBlocksBackground(true).enableInlineCodeBackground(true).build(),
		JBHtmlPaneConfiguration.builder()
			.iconResolver { name -> iconNamed(name) }
			.customStyleSheetProvider { pageStyles() }
			.build(),
	).apply {
		border = JBUI.Borders.empty(8, 12)
		background = UIUtil.getPanelBackground()
	}
	/**
	 * The rules a documentation page is laid out with: the classes of
	 * [DocumentationMarkup] (definition, content, sections), spaced the way
	 * the platform's documentation popup spaces them. The pane's border gives
	 * the outer padding.
	 */
	private fun pageStyles(): javax.swing.text.html.StyleSheet {
		val link = com.intellij.ui.ColorUtil.toHtmlColor(JBUI.CurrentTheme.Link.Foreground.ENABLED)
		val label = com.intellij.ui.ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
		val gap = JBUI.scale(8)
		val sheet = javax.swing.text.html.StyleSheet()
		sheet.addRule("html { padding: 0; margin: 0 }")
		sheet.addRule("body { padding: 0; margin: 0 }")
		sheet.addRule("pre { white-space: pre-wrap }")
		sheet.addRule("a { color: $link; text-decoration: none }")
		sheet.addRule(".definition { padding: ${gap / 2}px 0 ${gap}px 0 }")
		sheet.addRule(".definition pre { margin: 0; padding: 0 }")
		sheet.addRule(".content { padding: 0; max-width: 100% }")
		sheet.addRule(".bottom, .top { padding: ${gap / 2}px 0 ${gap / 2}px 0 }")
		sheet.addRule(".sections { padding: 0; border-spacing: 0 }")
		sheet.addRule(".section { color: $label; padding-right: 4px; white-space: nowrap }")
		return sheet
	}

	private val pageScroll = JBScrollPane(pane).apply {
		border = JBUI.Borders.empty()
		viewport.background = UIUtil.getPanelBackground()
	}
	private val pageHost = JBPanelWithEmptyText(BorderLayout())
	private val pageToolbar: javax.swing.JComponent
	private val splitter = OnePixelSplitter(false, 0.3f).apply { splitterProportionKey = "ADM.Documentation.split" }
	private var modules: List<Module> = emptyList()
	private var byName: Map<String, List<Symbol>> = emptyMap()
	private var loadedFor: String? = null
	private var rootDir: String? = null
	private var pageRequest = 0
	private val highlighter = ADMSyntaxHighlighter()
	/** Whether builtin and std modules are listed beside the project's own. */
	private var includeStd = true
	private var includeInternal = false
	/** A declaration to show once the model has loaded: absolute path and 1-based line. */
	private var pending: Pair<String, Int>? = null
	/** A symbol to show once the model has loaded: module and export name. */
	private var pendingSymbol: Pair<String, String>? = null
	/** `adm doc` is running; the Refresh button spins meanwhile. */
	private var loading = false
	/** Re-reads the model a moment after the last `.adm` change under the loaded root; while hidden, on the next show. */
	private val reloadAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
	private var stale = false
	/** Pages visited, as (module, entry name or null for the module page); [historyAt] is the current one. */
	private val history = ArrayList<Pair<String, String?>>()
	private var historyAt = -1
	private var restoring = false

	init {
		Disposer.register(parentDisposable, this)
		tree.isRootVisible = false
		tree.showsRootHandles = true
		tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
		tree.cellRenderer = object : ColoredTreeCellRenderer() {
			override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
				when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
					is Module -> {
						icon = ADMIcons.MODULE
						append(o.name)
						append("  ${o.entries.size}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
					}
					is Entry -> {
						icon = iconFor(o.first.kind)
						append(o.name)
						if (o.forms.size > 1) append("  ${o.forms.size}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
					}
					else -> append(o?.toString() ?: "")
				}
			}
		}
		tree.addTreeSelectionListener { showSelected() }
		object : DoubleClickListener() {
			override fun onDoubleClick(event: MouseEvent): Boolean = openSelected()
		}.installOn(tree)
		search.textEditor.emptyText.text = "Filter symbols"
		search.textEditor.document.addDocumentListener(object : DocumentListener {
			override fun insertUpdate(e: DocumentEvent) = rebuild()
			override fun removeUpdate(e: DocumentEvent) = rebuild()
			override fun changedUpdate(e: DocumentEvent) = rebuild()
		})

		val stdToggle = object : ToggleAction("Include Standard Library", "List builtin and std modules beside the project's own", AllIcons.Nodes.PpLib), DumbAware {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun isSelected(e: AnActionEvent) = includeStd
			override fun setSelected(e: AnActionEvent, state: Boolean) {
				includeStd = state
				rebuild()
			}
		}
		val internalToggle = object : ToggleAction("Include Internal Modules", "List modules visible inside their own package only", AllIcons.Nodes.Private), DumbAware {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun isSelected(e: AnActionEvent) = includeInternal
			override fun setSelected(e: AnActionEvent, state: Boolean) {
				includeInternal = state
				rebuild()
			}
		}
		val refresh = object : DumbAwareAction("Refresh", "Read the documentation again", AllIcons.Actions.Refresh) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.icon = if (loading) com.intellij.ui.AnimatedIcon.Default.INSTANCE else AllIcons.Actions.Refresh
				e.presentation.isEnabled = !loading
			}
			override fun actionPerformed(e: AnActionEvent) = reload()
		}
		val headerBar = ActionManager.getInstance().createActionToolbar("ADMDocumentationHeader", DefaultActionGroup(refresh, stdToggle, internalToggle), true)
		headerBar.targetComponent = this
		val left = JPanel(BorderLayout())
		search.border = JBUI.Borders.empty(4, 6, 4, 6)
		left.add(JPanel(BorderLayout()).apply {
			add(headerBar.component, BorderLayout.WEST)
			add(search, BorderLayout.CENTER)
		}, BorderLayout.NORTH)
		left.add(JBScrollPane(tree).apply { border = JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor()) }, BorderLayout.CENTER)

		val goto = object : DumbAwareAction("Go to Source", "Open the declaration in the editor", AllIcons.Actions.EditSource) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.isEnabled = (selectedObject() as? Entry)?.first?.file != null
			}
			override fun actionPerformed(e: AnActionEvent) {
				openSelected()
			}
		}
		val back = object : DumbAwareAction("Back", "Return to the previous page", AllIcons.Actions.Back) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.isEnabled = historyAt > 0
			}
			override fun actionPerformed(e: AnActionEvent) = step(-1)
		}
		val forward = object : DumbAwareAction("Forward", "Go to the next page", AllIcons.Actions.Forward) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.isEnabled = historyAt < history.size - 1
			}
			override fun actionPerformed(e: AnActionEvent) = step(1)
		}
		val toolbar = ActionManager.getInstance().createActionToolbar("ADMDocumentation", DefaultActionGroup(back, forward, goto), true)
		toolbar.targetComponent = pageHost
		pageToolbar = toolbar.component
		pageHost.emptyText.text = "Select a module or symbol"
		pane.addHyperlinkListener { e -> if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) navigate(e.description) }

		splitter.firstComponent = left
		splitter.secondComponent = pageHost
		emptyText.text = "Select a project to browse its documentation"

		// The model is a snapshot of the sources: a saved edit moves lines and
		// changes docs, so it is re-read after the edits settle.
		project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
			override fun after(events: List<VFileEvent>) {
				val root = loadedFor ?: return
				if (events.none { e -> e.path.endsWith(".adm") && (e.path == root || e.path.startsWith("$root/")) }) return
				reloadAlarm.cancelAllRequests()
				reloadAlarm.addRequest({
					if (isShowing) reload() else stale = true
				}, 1500)
			}
		})
		addHierarchyListener { e ->
			if (e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing && stale) {
				stale = false
				reload()
			}
		}
		ADMWorkspace.getInstance(project).addListener(object : ADMWorkspace.Listener {
			override fun selectionChanged(unit: ADMUnit?) = load(unit)
		}, this)
		load(ADMWorkspace.getInstance(project).selected)
	}

	// --- loading ---------------------------------------------------------------

	private fun load(unit: ADMUnit?) {
		val dir = unit?.projectDir
		if (dir == null) {
			removeAll()
			revalidate()
			repaint()
			return
		}
		if (dir == loadedFor && modules.isNotEmpty() && !loading) return
		loadedFor = dir
		loading = true
		// What the tree shows now, so a refresh lands where the user was.
		val expanded = (0 until tree.rowCount).filter { tree.isExpanded(it) }
			.mapNotNull { ((tree.getPathForRow(it).lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Module)?.name }
		val selected = when (val o = selectedObject()) {
			is Module -> o.name to null
			is Entry -> o.first.module to o.name
			else -> null
		}
		ADMCli.async(project, listOf("doc", "--workspace", "--json", "--full-doc", "--root", dir), dir) { r ->
			loading = false
			if (loadedFor != dir) return@async
			rootDir = dir
			modules = parse(r)
			byName = modules.flatMap { it.exports }.groupBy { it.name }
			if (splitter.parent !== this) add(splitter, BorderLayout.CENTER)
			emptyText.text = if (modules.isEmpty()) r.failure() else ""
			revalidate()
			repaint()
			rebuild()
			restore(expanded, selected)
			pending?.let { (path, line) ->
				pending = null
				showDeclaration(path, line)
			}
			pendingSymbol?.let { (module, name) ->
				pendingSymbol = null
				showSymbol(module, name)
			}
		}
	}

	/** Re-expands the modules that were open and re-selects the page that was shown, filter untouched. */
	private fun restore(expanded: List<String>, selected: Pair<String, String?>?) {
		for (i in 0 until root.childCount) {
			val mn = root.getChildAt(i) as DefaultMutableTreeNode
			val name = (mn.userObject as? Module)?.name ?: continue
			if (name in expanded) tree.expandPath(TreePath(mn.path))
			if (selected != null && name == selected.first) {
				var target: DefaultMutableTreeNode = mn
				if (selected.second != null) {
					for (j in 0 until mn.childCount) {
						val sn = mn.getChildAt(j) as DefaultMutableTreeNode
						if ((sn.userObject as? Entry)?.name == selected.second) target = sn
					}
				}
				restoring = true
				try {
					tree.selectionPath = TreePath(target.path)
				} finally {
					restoring = false
				}
			}
		}
	}

	/** Reads the selected unit's documentation again; the tree keeps its shape meanwhile. */
	private fun reload() {
		loadedFor = null
		load(ADMWorkspace.getInstance(project).selected)
	}

	/**
	 * Shows the page of [name] in [module]: the export of that name, else the
	 * type whose member it is. Waits for the model when it is still loading.
	 */
	fun showSymbol(module: String, name: String) {
		if (modules.isEmpty()) {
			pendingSymbol = module to name
			return
		}
		val m = modules.firstOrNull { it.name == module }
		if (m == null) {
			// The page lists the selected project's closure; a hover can name
			// a module outside it (std from a project that never imports it).
			// Fetch that module on its own and add it to the tree.
			fetchModule(module) { showSymbol(module, name) }
			return
		}
		val entry = m.entries.firstOrNull { it.name == name }
			?: m.entries.firstOrNull { e -> e.forms.any { f -> f.members.any { it.name == name } } }
			?: m.entries.firstOrNull { it.name == name.substringBefore('(').substringBefore('.') }
		if (entry == null) {
			// No such export: land on the module's page rather than nowhere.
			log.warn("documentation: $module has no export named $name")
			selectInTree(m.name, null)
			return
		}
		open(m, entry)
	}

	private val fetching = HashSet<String>()

	/** Loads one module's documentation (`adm doc --module`) into the model, then runs [then]. */
	private fun fetchModule(module: String, then: () -> Unit) {
		if (!fetching.add(module)) return
		ADMCli.async(project, listOf("doc", "--module", module, "--json", "--full-doc"), rootDir ?: project.basePath) { r ->
			fetching.remove(module)
			val o = r.json().asObjectOrNull()?.obj("module")
			if (o == null) {
				log.warn("documentation: adm doc --module $module failed: ${r.failure()}")
				return@async
			}
			val parsed = parseModule(o) ?: return@async
			modules = (modules.filter { it.name != parsed.name } + parsed).sortedWith(compareBy({ it.name.startsWith("std.") }, { it.name == "builtin" }, { it.name }))
			byName = modules.flatMap { it.exports }.groupBy { it.name }
			rebuild()
			then()
		}
	}

	/**
	 * Shows the page of the declaration at [path]:[line] (1-based): the export
	 * declared there, the type whose member is declared there, else the last
	 * export of that file declared above it. Waits for the model when it is
	 * still loading.
	 */
	fun showDeclaration(path: String, line: Int) {
		if (modules.isEmpty()) {
			pending = path to line
			return
		}
		val wanted = File(path).normalize().path
		fun at(x: Symbol) = x.file != null && File(resolve(x.file)).normalize().path == wanted
		var enclosing: Pair<Module, Entry>? = null
		var enclosingLine = -1
		for (m in modules) for (e in m.entries) for (f in e.forms) {
			if (!at(f)) continue
			if (f.line == line || f.members.any { it.line == line && at(it) }) {
				open(m, e)
				return
			}
			if (f.line in (enclosingLine + 1)..line) {
				enclosingLine = f.line
				enclosing = m to e
			}
		}
		enclosing?.let { open(it.first, it.second) }
	}

	private fun open(m: Module, e: Entry) {
		if (!includeStd && isStd(m)) {
			includeStd = true
			rebuild()
		}
		if (!includeInternal && m.internal) {
			includeInternal = true
			rebuild()
		}
		selectInTree(m.name, e.name)
	}

	private fun isStd(m: Module) = m.name == "builtin" || m.name.startsWith("std.")

	private fun parse(r: ADMCli.Result): List<Module> {
		val ws = r.json().asObjectOrNull()?.arr("workspace") ?: return emptyList()
		return ws.objects().mapNotNull(::parseModule).sortedWith(compareBy({ it.name.startsWith("std.") }, { it.name == "builtin" }, { it.name }))
	}

	private fun parseModule(o: JsonObject): Module? {
		val name = displayModule(o.str("name") ?: return null)
		return Module(name, o.bool("internal"), o.str("doc"), parseExamples(o), parseTags(o), o.arr("exports").objects().mapNotNull { parseSymbol(it, name) })
	}

	/** Package modules are named `__pkg__<namespace>.<module>` internally; the page shows the module. */
	private fun displayModule(raw: String): String = if (raw.startsWith("__pkg__")) raw.removePrefix("__pkg__").substringAfter('.') else raw

	private fun parseRefs(x: JsonObject, key: String): List<Ref> = x.arr(key).objects().mapNotNull { r ->
		val module = r.str("module") ?: return@mapNotNull null
		val name = r.str("name") ?: return@mapNotNull null
		Ref(displayModule(module), name)
	}

	private fun parseTags(x: JsonObject): List<ADMDocComments.Tag> = x.arr("tags").objects().mapNotNull { t ->
		t.str("name")?.let { ADMDocComments.Tag(it, t.str("text") ?: "") }
	}

	private fun parseExamples(x: JsonObject): List<Example> = x.arr("examples").objects().mapNotNull { ex ->
		ex.str("code")?.let { Example(ex.str("title")?.takeIf { t -> t.isNotBlank() }, it) }
	}

	private fun parseSymbol(x: JsonObject, module: String): Symbol? = x.str("name")?.let { name ->
		Symbol(
			name = name,
			kind = x.int("kind") ?: 0,
			kindName = x.str("kindName") ?: kindNameFor(x.int("kind") ?: 0),
			signature = x.str("signature"),
			doc = x.str("doc"),
			file = x.str("file"),
			line = x.int("line") ?: 0,
			column = x.int("column") ?: 0,
			type = x.str("type"),
			value = x.str("value"),
			valueInput = x.bool("valueInput"),
			bindInput = x.bool("bindInput"),
			state = x.bool("state"),
			scoped = x.bool("scoped"),
			implements = parseRefs(x, "implements"),
			implementedBy = parseRefs(x, "implementedBy"),
			examples = parseExamples(x),
			tags = parseTags(x),
			members = x.arr("members").objects().mapNotNull { parseSymbol(it, module) },
			module = module,
		)
	}

	private fun rebuild() {
		val filter = search.text.trim().lowercase()
		root.removeAllChildren()
		for (m in modules) {
			if (!includeStd && isStd(m)) continue
			if (!includeInternal && m.internal) continue
			val moduleMatches = m.name.lowercase().contains(filter)
			val entries = if (filter.isEmpty() || moduleMatches) m.entries else m.entries.filter { e ->
				e.name.lowercase().contains(filter) || e.forms.any { x -> x.members.any { it.name.lowercase().contains(filter) } }
			}
			if (filter.isNotEmpty() && entries.isEmpty() && !moduleMatches) continue
			val node = DefaultMutableTreeNode(m)
			for (e in entries) node.add(DefaultMutableTreeNode(e, false))
			root.add(node)
		}
		model.reload()
		if (filter.isNotEmpty()) for (i in 0 until tree.rowCount) tree.expandRow(i)
	}

	private fun selectedObject(): Any? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject

	// --- the page ------------------------------------------------------------

	/** Builds the page off the EDT (it reads source files for the declarations) and shows it when still current. */
	private fun showSelected() {
		val o = selectedObject()
		val request = ++pageRequest
		pageHost.removeAll()
		if (o !is Module && o !is Entry) {
			pageHost.revalidate()
			pageHost.repaint()
			return
		}
		record(o)
		ApplicationManager.getApplication().executeOnPooledThread {
			val html = runCatching {
				when (o) {
					is Module -> modulePage(o)
					is Entry -> symbolPage(o)
					else -> ""
				}
			}.getOrElse { DocumentationMarkup.CONTENT_START + "<p>" + esc(it.toString()) + "</p>" + DocumentationMarkup.CONTENT_END }
			ApplicationManager.getApplication().invokeLater({
				if (request != pageRequest) return@invokeLater
				pane.text = "<html><body>$html</body></html>"
				pane.caretPosition = 0
				pageHost.add(pageToolbar, BorderLayout.NORTH)
				pageHost.add(pageScroll, BorderLayout.CENTER)
				pageHost.revalidate()
				pageHost.repaint()
			}, project.disposed)
		}
	}

	// --- history --------------------------------------------------------------

	/** Appends the page to the history unless it is being revisited through Back/Forward. */
	private fun record(o: Any) {
		if (restoring) return
		val key = when (o) {
			is Module -> o.name to null
			is Entry -> o.first.module to o.name
			else -> return
		}
		if (historyAt >= 0 && history[historyAt] == key) return
		while (history.size > historyAt + 1) history.removeAt(history.size - 1)
		history.add(key)
		historyAt = history.size - 1
	}

	/** Moves [delta] pages through the history and selects that page in the tree. */
	private fun step(delta: Int) {
		val at = historyAt + delta
		if (at < 0 || at >= history.size) return
		historyAt = at
		val (module, entry) = history[at]
		restoring = true
		try {
			selectInTree(module, entry)
		} finally {
			restoring = false
		}
	}

	private fun header(b: StringBuilder, module: String) {
		b.append(DocumentationMarkup.CONTENT_START)
		b.append("<p>Module: <a href='mod:").append(esc(module)).append("'>").append(esc(module)).append("</a></p>")
		b.append(DocumentationMarkup.CONTENT_END)
	}

	private fun title(b: StringBuilder, icon: String, name: String) {
		b.append(DocumentationMarkup.CONTENT_START)
		b.append("<p><icon src='").append(icon).append("'>&nbsp;<b>").append(esc(name)).append("</b></p>")
		b.append(DocumentationMarkup.CONTENT_END)
	}

	/** The grey box hover popups draw declarations in: the editor's caret-row shade, which follows the scheme. */
	private fun boxColor(): String {
		val scheme = EditorColorsManager.getInstance().globalScheme
		val c = scheme.getColor(com.intellij.openapi.editor.colors.EditorColors.CARET_ROW_COLOR) ?: UIUtil.getPanelBackground().darker()
		return "#" + ColorUtil.toHex(c)
	}

	private fun definition(b: StringBuilder, code: String, self: Symbol?) {
		b.append("<div class='definition' style='background-color:").append(boxColor()).append(";padding:6px 8px;margin:4px 0'>")
		b.append(colorize(code, self))
		b.append("</div>")
	}

	private fun content(b: StringBuilder, markdown: String?) {
		val text = markdown?.takeIf { it.isNotBlank() } ?: return
		val html = ADMMarkdown.toHtml(text) { colorizeBody(it, null) }
			.replace("<div class=\"code-block\">", "<div class=\"code-block\" style='background-color:${boxColor()};padding:6px 8px'>")
		b.append(DocumentationMarkup.CONTENT_START).append(html).append(DocumentationMarkup.CONTENT_END)
	}

	/**
	 * The rows a doc comment's trailing tags make, Author, See Also, Since
	 * first. A See Also target links to its page in [module]: the export of
	 * that name, or the type a `Type.member` names.
	 */
	private fun tags(b: StringBuilder, tags: List<ADMDocComments.Tag>, module: String) {
		if (tags.isEmpty()) return
		b.append(DocumentationMarkup.SECTIONS_START)
		for (g in ADMDocComments.group(tags)) {
			b.append(DocumentationMarkup.SECTION_HEADER_START).append(esc(g.label)).append(':').append(DocumentationMarkup.SECTION_SEPARATOR)
			g.values.forEachIndexed { i, v ->
				if (i > 0) b.append(", ")
				if (g.name == "see" && v.isNotBlank()) {
					val export = v.substringBefore('(').substringBefore('.').trim()
					b.append("<a href='sym:").append(esc(module)).append(':').append(esc(export)).append("'><code>").append(esc(v)).append("</code></a>")
				} else {
					b.append(esc(v))
				}
			}
			b.append(DocumentationMarkup.SECTION_END).append("</tr>")
		}
		b.append(DocumentationMarkup.SECTIONS_END)
	}

	private fun sectionTitle(b: StringBuilder, text: String) {
		b.append(DocumentationMarkup.CONTENT_START).append("<p><b>").append(esc(text)).append("</b></p>").append(DocumentationMarkup.CONTENT_END)
	}

	private fun modulePage(m: Module): String {
		val b = StringBuilder()
		title(b, "MODULE", m.name)
		definition(b, "module ${m.name}", null)
		if (m.internal) b.append(DocumentationMarkup.CONTENT_START).append("<p>").append(grayed("internal: visible inside its package only")).append("</p>").append(DocumentationMarkup.CONTENT_END)
		content(b, m.doc)
		tags(b, m.tags, m.name)
		for ((kind, group) in m.exports.groupBy { it.kindName }) {
			sectionTitle(b, plural(kind))
			for (x in group) {
				definition(b, if (x.kindName == "const" || x.kindName == "let") valueDeclaration(x) else declaration(x, withAnnotations = false) ?: fallbackDeclaration(x), x)
				firstSentence(x.doc)?.let { b.append(DocumentationMarkup.CONTENT_START).append("<p>").append(esc(it)).append("</p>").append(DocumentationMarkup.CONTENT_END) }
			}
		}
		examples(b, m.examples.map { null to it })
		return b.toString()
	}

	private fun symbolPage(e: Entry): String {
		val b = StringBuilder()
		header(b, e.first.module)
		title(b, iconName(e.first.kind), e.name)
		for (x in e.forms) {
			definition(b, typeDeclaration(x), null)
			content(b, x.doc)
			tags(b, x.tags, x.module)
			if (e.forms.size > 1) location(b, x)
		}
		val x = e.first
		if (e.forms.size == 1 && x.isTypeLike) {
			relation(b, "Implements", x.implements, x.module)
			relation(b, "Implemented by", x.implementedBy, x.module)
			members(b, x)
		}
		val all = ArrayList<Pair<String?, Example>>()
		for (form in e.forms) {
			form.examples.forEach { all.add(null to it) }
			for (m in form.members) m.examples.forEach { all.add(m.name to it) }
		}
		examples(b, all)
		if (e.forms.size == 1) location(b, x)
		return b.toString()
	}

	/**
	 * The Examples section: the page's own examples, then its members', each
	 * as its title (prefixed by the member's name) over a coloured code box.
	 */
	private fun examples(b: StringBuilder, all: List<Pair<String?, Example>>) {
		if (all.isEmpty()) return
		sectionTitle(b, "Examples")
		for ((owner, ex) in all) {
			val label = listOfNotNull(owner, ex.title).joinToString(": ")
			if (label.isNotEmpty()) {
				b.append(DocumentationMarkup.CONTENT_START).append("<p>").append(esc(label)).append("</p>").append(DocumentationMarkup.CONTENT_END)
			}
			definition(b, ex.code, null)
		}
	}

	/** The type-like symbols a page is related to, each a link to its own page; those outside [module] name theirs. */
	private fun relation(b: StringBuilder, label: String, refs: List<Ref>, module: String) {
		if (refs.isEmpty()) return
		b.append(DocumentationMarkup.CONTENT_START).append("<p><b>").append(esc(label)).append("</b>: ")
		refs.forEachIndexed { i, r ->
			if (i > 0) b.append(", ")
			b.append("<a href='sym:").append(esc(r.module)).append(':').append(esc(r.name)).append("'>").append(esc(r.name)).append("</a>")
			if (r.module != module) b.append(grayed(" ${r.module}"))
		}
		b.append("</p>").append(DocumentationMarkup.CONTENT_END)
	}

	private fun location(b: StringBuilder, x: Symbol) {
		val file = x.file ?: return
		b.append(DocumentationMarkup.CONTENT_START).append("<p>").append(grayed("${ADMPanelActions.relative(project, resolve(file))}:${x.line}")).append("</p>").append(DocumentationMarkup.CONTENT_END)
	}

	/**
	 * A type's members, in source order within each group. Fields declared
	 * together (`c, m, y, k float`) make one row carrying the group's doc.
	 */
	private fun members(b: StringBuilder, x: Symbol) {
		val fields = x.members.filter { it.kindName == "field" }
		if (fields.isNotEmpty()) {
			sectionTitle(b, "Fields")
			var i = 0
			while (i < fields.size) {
				val group = ArrayList<Symbol>()
				group.add(fields[i])
				while (i + 1 < fields.size && fields[i + 1].file == fields[i].file && fields[i + 1].line == fields[i].line) {
					group.add(fields[++i])
				}
				i++
				definition(b, memberLine(group), null)
				content(b, group.firstNotNullOfOrNull { f -> f.doc?.takeIf { it.isNotBlank() } })
			}
		}
		// A component's surface is what a view can give it, watch on it and
		// hear from it: its inputs, its states and its events. The
		// controller's other members are the component's own business and
		// the model leaves them out.
		for ((kind, name) in listOf("input" to "Inputs", "state" to "States", "emit" to "Emits")) {
			val group = x.members.filter { it.kindName == kind }
			if (group.isEmpty()) continue
			sectionTitle(b, name)
			for (m in group) {
				definition(b, if (kind == "emit") declaration(m) ?: memberLine(listOf(m)) else memberLine(listOf(m)), null)
				content(b, m.doc)
				tags(b, m.tags, m.module)
			}
		}
		val groups = linkedMapOf<String, MutableList<Symbol>>()
		for (m in x.members) {
			val group = when (m.kindName) {
				"method", "function" -> when {
					m.name == "new" -> "Constructors"
					m.name == "dispose" -> "Destructor"
					(declaration(m, withAnnotations = false) ?: "").contains("infix ") -> "Infix"
					else -> "Methods"
				}
				"property" -> "Properties"
				"const" -> "Constants"
				"enum member" -> "Values"
				"union variant" -> "Variants"
				else -> null
			} ?: continue
			groups.getOrPut(group) { ArrayList() }.add(m)
		}
		for (name in listOf("Constructors", "Destructor", "Properties", "Methods", "Infix", "Constants", "Values", "Variants")) {
			val group = groups[name] ?: continue
			sectionTitle(b, name)
			for (m in group) {
				definition(b, if (m.kindName in VALUE_KINDS) memberLine(listOf(m)) else declaration(m) ?: fallbackDeclaration(m), null)
				content(b, m.doc)
				tags(b, m.tags, m.module)
			}
		}
	}

	/**
	 * One member as the hover shows it, from the model rather than the source
	 * line, so names declared together each get their own type: `c float`,
	 * `Normal = 1`, `Some T`, `width int { get set }`.
	 */
	private fun memberLine(group: List<Symbol>): String {
		val m = group.first()
		val names = group.joinToString(", ") { it.name }
		val type = m.type?.takeIf { it.isNotBlank() }
		val value = m.value?.takeIf { it.isNotBlank() }
		return when (m.kindName) {
			"enum member" -> if (value != null) "$names = $value" else names
			"property" -> listOfNotNull(names, type, "{ get set }").joinToString(" ")
			"input", "state" -> listOfNotNull(inputAnnotation(m), if (m.state) "@state" else null, names, type, value?.let { "= $it" }).joinToString(" ")
			// The model's type carries the parameters with their names; the
			// recorded signature, without them, stands in.
			"emit" -> "@emit() def $names" + (type ?: (m.signature ?: "").let { it.substringAfter("::", it) })
			else -> listOfNotNull(names, type, value?.let { "= $it" }).joinToString(" ")
		}
	}

	/** The `@input` annotation of a component's input as its flags spell it; null for a state that is no input. */
	private fun inputAnnotation(m: Symbol): String? {
		if (m.kindName != "input") return null
		val flags = listOfNotNull(if (m.valueInput) "value = true" else null, if (m.bindInput) "bind = true" else null)
		return if (flags.isEmpty()) "@input" else "@input(${flags.joinToString(", ")})"
	}

	/**
	 * A type's declaration the way the hover shows it: the header line with
	 * its fields (or values, variants) inside the braces. Other symbols: the
	 * declaration as written.
	 */
	private fun typeDeclaration(x: Symbol): String {
		if (x.kindName == "const" || x.kindName == "let") return valueDeclaration(x)
		// A scoped component is marked on its card even when the source
		// line could not be read (the model says so).
		val head = (declaration(x) ?: fallbackDeclaration(x)).let { if (x.scoped && !it.contains("@scoped")) "@scoped\n$it" else it }
		if (!x.isTypeLike) return head
		val inner = x.members.filter { it.kindName in SHAPE_KINDS }.map { m -> "    " + memberLine(listOf(m)) }
		if (inner.isEmpty()) return head
		return head.trimEnd() + " {\n" + inner.joinToString("\n") + "\n}"
	}

	/**
	 * The declaration as written in the source: annotation lines directly
	 * above, then from the symbol's line up to its body's opening brace,
	 * indentation removed. Null when the file is not readable.
	 */
	private fun declaration(x: Symbol, withAnnotations: Boolean = true): String? {
		val file = x.file ?: return null
		if (x.line <= 0) return null
		val lines = runCatching { File(resolve(file)).readLines() }.getOrNull() ?: return null
		// The model's line is where the declaration was when `adm doc` ran; an
		// edit since may have moved it, so the line is checked and the nearest
		// declaration of the name used instead. None nearby: the fallback.
		val line = declarationLine(lines, x.name, x.line) ?: return null
		val out = ArrayList<String>()
		if (withAnnotations) {
			var j = line - 2
			val above = ArrayList<String>()
			while (j >= 0 && lines[j].trim().startsWith("@")) {
				above.add(0, lines[j])
				j--
			}
			out.addAll(above)
		}
		var i = line - 1
		var taken = 0
		while (i < lines.size && taken < 8) {
			val raw = lines[i]
			val brace = raw.indexOf('{')
			val cut = (if (brace >= 0) raw.substring(0, brace) else raw).trimEnd()
			out.add(cut)
			taken++
			// The header ends at its brace; a parameter list may spill over lines that end in "," or "(".
			if (brace >= 0 || !(cut.endsWith(",") || cut.endsWith("("))) break
			i++
		}
		val indent = out.lastOrNull()?.takeWhile { it == ' ' || it == '\t' } ?: ""
		return out.joinToString("\n") { it.removePrefix(indent) }.trim().ifEmpty { null }
	}

	/** When the source is out of reach: the resolver's compact signature. */
	/** The 1-based line declaring [name]: [expected] when it does, else the nearest one within 80 lines that does. */
	private fun declarationLine(lines: List<String>, name: String, expected: Int): Int? {
		val declares = Regex("""^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:internal|partial|static|meta|async|atomic|weak|public)\s+)*(?:def|type|struct|enum|union|interface|datatype|service|component|style|view|const|let|module|check|application|library|plugin)\s+${Regex.escape(name)}\b|^\s*${Regex.escape(name)}\s*(?:,|=|\s+[A-Za-z_\[])""")
		fun ok(n: Int) = n in 1..lines.size && declares.containsMatchIn(lines[n - 1])
		if (ok(expected)) return expected
		for (d in 1..80) {
			if (ok(expected + d)) return expected + d
			if (ok(expected - d)) return expected - d
		}
		return null
	}

	/** A module constant or variable: `const NAME TYPE = VALUE`, from the model's type and value. */
	private fun valueDeclaration(x: Symbol): String = "${x.kindName} ${memberLine(listOf(x))}"

	private fun fallbackDeclaration(x: Symbol): String {
		val sig = x.signature ?: ""
		return when (x.kindName) {
			"function", "method" -> "def ${x.name}${sig.substringAfter("::", sig)}"
			"field", "property", "const", "let", "parameter", "enum member", "union variant" -> "${x.name} $sig".trim()
			else -> "${x.kindName} ${x.name}"
		}
	}

	/**
	 * ADM code coloured by the plugin's highlighter with the editor's colour
	 * scheme, type names linked to their pages. [self], when given, is the
	 * symbol whose own name links to its page (module listings).
	 */
	private fun colorize(code: String, self: Symbol?): String = "<pre>" + colorizeBody(code, self) + "</pre>"

	/** The spans of [colorize] without the `<pre>`, for code blocks inside prose. */
	private fun colorizeBody(code: String, self: Symbol?): String {
		val scheme = EditorColorsManager.getInstance().globalScheme
		val lexer = ADMLexer()
		lexer.start(code)
		data class Tok(val type: com.intellij.psi.tree.IElementType?, val text: String)
		val toks = ArrayList<Tok>()
		while (lexer.tokenType != null) {
			toks.add(Tok(lexer.tokenType, code.substring(lexer.tokenStart, lexer.tokenEnd)))
			lexer.advance()
		}
		val b = StringBuilder()
		var declaredNext = false
		for ((i, t) in toks.withIndex()) {
			val text = esc(t.text)
			val key = highlighter.getTokenHighlights(t.type).firstOrNull()
			val attrs = key?.let { scheme.getAttributes(it) }
			var span = text
			if (attrs != null && (attrs.foregroundColor != null || attrs.fontType != Font.PLAIN)) {
				val style = StringBuilder()
				attrs.foregroundColor?.let { style.append("color:#").append(ColorUtil.toHex(it)).append(';') }
				if (attrs.fontType and Font.BOLD != 0) style.append("font-weight:bold;")
				if (attrs.fontType and Font.ITALIC != 0) style.append("font-style:italic;")
				span = "<span style=\"$style\">$text</span>"
			}
			if (t.type == ADMTokenTypes.IDENTIFIER) {
				val prev = toks.subList(0, i).lastOrNull { it.type != ADMTokenTypes.WHITE_SPACE }
				val target = when {
					declaredNext -> self?.takeIf { it.name == t.text }
					prev?.type == ADMTokenTypes.DOT -> {
						val qualifier = toks.subList(0, i - 1).lastOrNull { it.type != ADMTokenTypes.WHITE_SPACE && it.type != ADMTokenTypes.DOT }?.text
						resolve(t.text, qualifier)
					}
					else -> resolve(t.text, null)
				}
				if (target != null) span = "<a href='sym:${esc(target.module)}:${esc(target.name)}'>$span</a>"
			}
			declaredNext = t.type == ADMTokenTypes.ROOT_DECLARATION || (t.type == ADMTokenTypes.KEYWORD && t.text in DECLARING_KEYWORDS)
			b.append(span)
		}
		return b.toString()
	}

	/** The type [name] refers to, [qualifier] being a module alias or the last segment of its name. */
	private fun resolve(name: String, qualifier: String?): Symbol? {
		val candidates = byName[name]?.filter { it.isTypeLike } ?: return null
		if (candidates.isEmpty()) return null
		if (qualifier != null) {
			candidates.firstOrNull { it.module == qualifier || it.module.endsWith(".$qualifier") }?.let { return it }
			return null
		}
		return candidates.singleOrNull() ?: candidates.firstOrNull { it.module == "builtin" }
	}

	private fun firstSentence(text: String?): String? {
		val t = text?.trim()?.replace('\n', ' ')?.takeIf { it.isNotEmpty() } ?: return null
		val i = t.indexOf(". ")
		return if (i > 0) t.substring(0, i + 1) else t
	}

	private fun plural(kind: String): String = when (kind) {
		"property" -> "Properties"
		"const" -> "Constants"
		"let" -> "Variables"
		"enum member" -> "Values"
		"union variant" -> "Variants"
		else -> kind.replaceFirstChar { it.uppercase() } + "s"
	}

	private fun grayed(text: String): String = "<span class='grayed'>${esc(text)}</span>"

	private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

	// --- navigation -------------------------------------------------------------

	/** `mod:NAME` selects a module, `sym:MODULE:NAME` an export; the tree selection then renders the page. */
	private fun navigate(href: String?) {
		href ?: return
		val module: String
		val symbol: String?
		when {
			href.startsWith("mod:") -> {
				module = href.removePrefix("mod:")
				symbol = null
			}
			href.startsWith("sym:") -> {
				val rest = href.removePrefix("sym:")
				module = rest.substringBefore(':')
				symbol = rest.substringAfter(':')
			}
			else -> return
		}
		selectInTree(module, symbol)
	}

	/** Selects [module]'s node, or its [symbol] entry, clearing the filter when it hides them; the selection renders the page. */
	private fun selectInTree(module: String, symbol: String?) {
		if (search.text.isNotEmpty()) {
			search.text = ""
			rebuild()
		}
		for (i in 0 until root.childCount) {
			val mn = root.getChildAt(i) as DefaultMutableTreeNode
			if ((mn.userObject as? Module)?.name != module) continue
			var target: DefaultMutableTreeNode = mn
			if (symbol != null) {
				for (j in 0 until mn.childCount) {
					val sn = mn.getChildAt(j) as DefaultMutableTreeNode
					if ((sn.userObject as? Entry)?.name == symbol) {
						target = sn
						break
					}
				}
			}
			val path = TreePath(target.path)
			tree.expandPath(TreePath(mn.path))
			tree.selectionPath = path
			tree.scrollPathToVisible(path)
			return
		}
	}

	/** `adm doc` prints paths relative to the directory it ran in. */
	private fun resolve(path: String): String {
		val f = File(path)
		return if (f.isAbsolute) f.path else File(rootDir ?: project.basePath ?: ".", path).normalize().path
	}

	private fun openSelected(): Boolean {
		val x = (selectedObject() as? Entry)?.first ?: return false
		val vf = x.file?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(resolve(it)) } ?: return false
		OpenFileDescriptor(project, vf, (x.line - 1).coerceAtLeast(0), 0).navigate(true)
		return true
	}

	// --- kinds and icons ------------------------------------------------------------

	private fun kindNameFor(kind: Int): String = when (kind) {
		1 -> "module"; 2 -> "application"; 3 -> "library"; 4 -> "plugin"; 5 -> "check"; 6 -> "type"; 7 -> "struct"; 8 -> "enum"
		9 -> "union"; 10 -> "interface"; 11 -> "datatype"; 12 -> "service"; 13 -> "component"; 14 -> "view"; 15 -> "style"
		16 -> "property"; 17 -> "field"; 18 -> "function"; 19 -> "method"; 20 -> "const"; 21 -> "let"; 22 -> "parameter"
		25 -> "enum member"; 26 -> "union variant"; else -> "symbol"
	}

	private fun iconName(kind: Int): String = when (kind) {
		1 -> "MODULE"; 2 -> "APPLICATION"; 3 -> "LIBRARY"; 4 -> "PLUGIN"; 5 -> "CHECK"; 6 -> "TYPE"; 7 -> "STRUCT"; 8 -> "ENUM"
		9 -> "UNION"; 10 -> "INTERFACE"; 11 -> "DATATYPE"; 12 -> "SERVICE"; 13 -> "COMPONENT"; 14 -> "VIEW"; 15 -> "STYLE"
		16 -> "PROPERTY"; 17 -> "FIELD"; 18 -> "FUNCTION"; 19 -> "METHOD"; 20 -> "CONSTANT"; 21 -> "VARIABLE"; 22 -> "PARAMETER"
		25 -> "ENUM_VALUE"; 26 -> "UNION_VARIANT"; else -> "MODULE"
	}

	private fun iconFor(kind: Int): Icon = iconNamed(iconName(kind)) ?: AllIcons.Nodes.Unknown

	/** The `<icon src='NAME'>` names the page uses, from [ADMIcons]. */
	private fun iconNamed(name: String): Icon? = when (name) {
		"MODULE" -> ADMIcons.MODULE; "APPLICATION" -> ADMIcons.APPLICATION; "LIBRARY" -> ADMIcons.LIBRARY; "PLUGIN" -> ADMIcons.PLUGIN
		"CHECK" -> ADMIcons.CHECK; "TYPE" -> ADMIcons.TYPE; "STRUCT" -> ADMIcons.STRUCT; "ENUM" -> ADMIcons.ENUM; "UNION" -> ADMIcons.UNION
		"INTERFACE" -> ADMIcons.INTERFACE; "DATATYPE" -> ADMIcons.DATATYPE; "SERVICE" -> ADMIcons.SERVICE; "COMPONENT" -> ADMIcons.COMPONENT
		"VIEW" -> ADMIcons.VIEW; "STYLE" -> ADMIcons.STYLE; "PROPERTY" -> ADMIcons.PROPERTY; "FIELD" -> ADMIcons.FIELD
		"FUNCTION" -> ADMIcons.FUNCTION; "METHOD" -> ADMIcons.METHOD; "CONSTANT" -> ADMIcons.CONSTANT; "VARIABLE" -> ADMIcons.VARIABLE
		"PARAMETER" -> ADMIcons.PARAMETER; "ENUM_VALUE" -> ADMIcons.ENUM_VALUE; "UNION_VARIANT" -> ADMIcons.UNION_VARIANT
		else -> null
	}

	override fun dispose() {}

	companion object {
		/** Opens the ADM tool window on the Documentation tab at the declaration at [path]:[line] (1-based). */
		fun reveal(project: Project, path: String, line: Int) = withPanel(project) { it.showDeclaration(path, line) }

		/** Opens the ADM tool window on the Documentation tab at [name] of [module]. */
		fun revealSymbol(project: Project, module: String, name: String) = withPanel(project) { it.showSymbol(module, name) }

		private fun withPanel(project: Project, action: (ADMDocumentationPanel) -> Unit) {
			val window = ToolWindowManager.getInstance(project).getToolWindow("ADM")
			if (window == null) {
				log.warn("ADM tool window is not registered in ${project.name}")
				return
			}
			window.activate({
				// Reading the content manager creates the tabs when the window
				// was never opened; the factory registers every panel at once.
				val manager = window.contentManager
				val content = manager.contents.firstOrNull { it.component is ADMDocumentationPanel }
				if (content == null) {
					log.warn("ADM tool window has no Documentation tab (${manager.contents.map { it.displayName }})")
					return@activate
				}
				manager.setSelectedContent(content)
				action(content.component as ADMDocumentationPanel)
			}, true)
		}

		private val log = com.intellij.openapi.diagnostic.Logger.getInstance(ADMDocumentationPanel::class.java)

		private val TYPE_KINDS = setOf("type", "struct", "enum", "union", "interface", "datatype", "service", "component", "style", "view")
		/** Members drawn inside a type's braces. */
		private val SHAPE_KINDS = setOf("field", "enum member", "union variant", "property", "input", "state", "emit")
		/** Members rendered from the model's type and value rather than their source line. */
		private val VALUE_KINDS = setOf("field", "enum member", "union variant", "const", "let", "input", "state", "emit")
		private val DECLARING_KEYWORDS = setOf("def", "type", "struct", "enum", "union", "interface", "datatype", "service", "component", "style", "view", "module", "meta")
	}
}
