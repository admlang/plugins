package org.adm.intellij.ide

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel

/**
 * The Info tab: what `adm.toml` says about the selected project. A list of
 * sections on the left (the unit itself, Publisher, Updates, Package, Policy,
 * Dependencies, Registries, the file in an editor); the right shows the
 * selected section's form. The forms read `adm app info --json` and write
 * every change back with `adm app set` / `adm app unset`; a project without
 * a manifest shows a warning and creates one with `adm app init`.
 */
class ADMInfoPanel(private val project: Project, parentDisposable: Disposable) : JBPanelWithEmptyText(BorderLayout()), Disposable {
	private val sections = ADMSectionsView("ADM.Info.sections")
	private val rawHost = JPanel(BorderLayout())
	private val banner = JPanel(BorderLayout()).apply { isVisible = false }
	private val bannerText = JBLabel("", AllIcons.General.Warning, JBLabel.LEFT)
	private var saving = false
	private var unit: ADMUnit? = null
	private var editor: Editor? = null

	/** Form controls by the key they read from the info tree; [writes] maps those to `adm app set` keys. */
	private val fields = LinkedHashMap<String, JComponent>()
	private val writes = LinkedHashMap<String, String>()
	private var loaded: Map<String, String> = emptyMap()
	private var registries: RegistriesEditor? = null
	private var files: FilesEditor? = null

	init {
		Disposer.register(parentDisposable, this)
		val save = object : DumbAwareAction("Save", "Write the changes to adm.toml", AllIcons.Actions.MenuSaveall) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.isEnabled = !saving && unit != null
			}
			override fun actionPerformed(e: AnActionEvent) = save()
		}
		val refresh = object : DumbAwareAction("Refresh", "Read adm.toml again", AllIcons.Actions.Refresh) {
			override fun getActionUpdateThread() = ActionUpdateThread.EDT
			override fun update(e: AnActionEvent) {
				e.presentation.isEnabled = unit != null
			}
			override fun actionPerformed(e: AnActionEvent) {
				unit?.let { bind(it) }
			}
		}
		val toolbar = ActionManager.getInstance().createActionToolbar("ADMInfo", DefaultActionGroup(save, refresh), true)
		toolbar.targetComponent = this
		val header = JPanel(BorderLayout()).apply {
			border = JBUI.Borders.empty(2, 6, 0, 6)
			add(toolbar.component, BorderLayout.WEST)
		}
		banner.border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.Banner.WARNING_BORDER_COLOR), JBUI.Borders.empty(8, 12))
		banner.background = JBUI.CurrentTheme.Banner.WARNING_BACKGROUND
		banner.add(bannerText, BorderLayout.CENTER)
		banner.add(ActionLink("Create adm.toml") { createManifest() }, BorderLayout.EAST)

		val top = JPanel(BorderLayout()).apply {
			add(banner, BorderLayout.NORTH)
			add(header, BorderLayout.SOUTH)
		}
		add(top, BorderLayout.NORTH)
		add(sections, BorderLayout.CENTER)
		emptyText.text = "Select a project"

		ADMWorkspace.getInstance(project).addListener(object : ADMWorkspace.Listener {
			override fun selectionChanged(unit: ADMUnit?) = bind(unit)
		}, this)
		bind(ADMWorkspace.getInstance(project).selected)
	}

	private fun bind(unit: ADMUnit?) {
		this.unit = unit
		val visible = unit != null
		for (c in components) c.isVisible = visible
		if (unit == null) return
		val missing = unit.manifest == null
		banner.isVisible = missing
		bannerText.text = "No adm.toml in ${ADMPanelActions.relative(project, unit.dir)}. Saving creates one, with the publisher keys `adm app init` generates."
		buildRaw(unit.manifest)
		loadForm(unit)
	}

	// --- raw ----------------------------------------------------------------

	/** The manifest in a real editor: TOML highlighting, scrollbars, and a margin like a file tab. */
	private fun buildRaw(path: String?) {
		rawHost.removeAll()
		releaseEditor()
		val vf = path?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
		val doc = vf?.let { FileDocumentManager.getInstance().getDocument(it) }
		if (doc != null) {
			val e = EditorFactory.getInstance().createEditor(doc, project) as EditorEx
			val type = FileTypeManager.getInstance().getFileTypeByExtension("toml")
			e.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, type)
			e.settings.apply {
				isLineNumbersShown = true
				isFoldingOutlineShown = false
				isLineMarkerAreaShown = false
				additionalLinesCount = 2
				isRightMarginShown = false
			}
			e.setBorder(JBUI.Borders.empty())
			e.component.border = JBUI.Borders.empty(8, 8, 0, 8)
			editor = e
			rawHost.add(e.component, BorderLayout.CENTER)
		}
		rawHost.revalidate()
		rawHost.repaint()
	}

	private fun releaseEditor() {
		editor?.let { EditorFactory.getInstance().releaseEditor(it) }
		editor = null
	}

	// --- form ---------------------------------------------------------------

	private fun loadForm(unit: ADMUnit) {
		val dir = unit.projectDir
		ADMCli.async(project, listOf("app", "info", "--json", "--dir", dir, "--app", unit.name), dir) { r ->
			if (this.unit?.id != unit.id) return@async
			val o = r.json().asObjectOrNull()
			val error = if (r.ok) null else r.failure()
			// The Dependencies page lists what adm.lock holds, not only the
			// [deps] ranges: a library installed with adm get is a dependency
			// whether or not the manifest names a range for it.
			ADMCli.async(project, listOf("list", "packages", "--json", "--dir", dir), dir) { l ->
				if (this.unit?.id != unit.id) return@async
				val packages = if (l.ok) l.json().asArrayOrNull().objects() else emptyList()
				buildForm(unit, o, error, packages)
			}
		}
	}

	/**
	 * One text field of the form: the key in the flattened `adm app info` tree
	 * it reads, the `adm app set` key it writes (null = read-only) and the hint
	 * shown under it.
	 */
	private class Field(val label: String, val read: String, val write: String?, val hint: String, val kind: Kind = Kind.Text, val bold: Boolean = false, val options: List<String> = emptyList()) {
		enum class Kind { Text, Folder, File, Pick, Choice, Permissions, Version }
	}

	/** The section named after the unit's kind: Application, Library or Plugin. */
	private fun kindTitle(unit: ADMUnit): String = unit.kind.label.replaceFirstChar { it.uppercase() }

	/** The unit's own `[apps.<Name>]` table overrides the file-level ones; a library's version lives on its declaration. */
	private fun layout(unit: ADMUnit): List<Pair<String, List<Field>>> {
		val a = "apps." + unit.name
		val kind = unit.kind.label
		val application = unit.kind == ADMUnit.Kind.Application
		return listOf(
			kindTitle(unit) to listOf(
				Field("Name", "resolved.appName", null, "The `$kind` block's name; it is the [apps.<Name>] table in adm.toml.", bold = true),
				Field("Id", "resolved.appId", null, "Derived from the publisher key, the repo salt and the slug, so it survives renames.", bold = true),
				Field("Slug", "$a.slug", "slug", "Stable short name used for the id and the home folder; defaults to the $kind name."),
				if (application) Field("Version", "$a.version", "version", "Semantic version; Bump raises a part, Save writes it.", Field.Kind.Version)
				else Field("Version", "$a.version", null, "From `@manifest(version)` on the $kind declaration; Publish raises it (patch, minor or major) before publishing."),
				Field("Description", "$a.description", "description", "One line saying what it is: `adm publish` files it with the library and `adm search` shows it."),
				Field("Platforms", "$a.compat.os", "compat.os", "Operating systems this works on; Any means all. Informational: the compiler enforces reach through @os.", Field.Kind.Pick, options = PLATFORMS),
				Field("Architectures", "$a.compat.arch", "compat.arch", "Processor architectures this works on; Any means all. Informational: the compiler enforces reach through @arch.", Field.Kind.Pick, options = ARCHITECTURES),
				Field("Minimum ADM", "$a.compat.adm", "compat.adm", "Oldest compiler version that builds it, e.g. 0.1.1500. Empty means any."),
			),
			"Publisher" to listOf(
				Field("Name", "publisher.name", "publisher.name", "Display name shown next to published packages. Not part of the identity."),
				Field("Id", "publisher.id", null, "Derived from the Ed25519 public key; the registry records it against every version you publish."),
				Field("Public key", "publisher.pubkey", null, "Ed25519, base64. Consumers verify every package and update against it."),
				Field("Repo salt", "publisher.repoSalt", null, "Random per-repository value mixed into every application id so ids survive renames. Never shown."),
				Field("Private key", "publisher.privateKey", null, "Where the signing key lives; `adm app init` printed it. Never commit it. In CI, ADM_SIGNING_KEY replaces the file."),
				Field("Created", "publisher.created", null, "When `adm app init` generated the key pair and the repo salt."),
			),
			"Updates" to listOf(
				Field("Feed URL", "$a.update.feed", "update.feed", "Where the built application looks for new releases (reaches the program as `__update_feed__`)."),
				Field("Channel", "$a.update.channel", "update.channel", "Release channel to follow, e.g. `stable` or `beta`."),
				Field("Artifact", "$a.update.artifact", "update.artifact", "Artifact name to download from the feed, e.g. `bundle` or `exe`."),
			),
			"Package" to listOf(
				Field("Format", "$a.package.format", "package.format", "Archive format `adm package build` produces.", Field.Kind.Choice, options = PACKAGE_FORMATS),
				Field("Install root", "$a.package.installRoot", "package.installRoot", "Where `adm package install` unpacks the archive on the target machine, e.g. /opt/<app>.", Field.Kind.Folder),
				Field("Binary link", "$a.package.binLink", "package.binLink", "Symlink `adm package install` creates so the executable is on PATH, e.g. /usr/local/bin/<app>. Leave empty for none.", Field.Kind.File),
			),
			"Policy" to listOf(
				Field("Required permissions", "requires", "requires.permissions", "Narrows what the package may be granted. Pick from every permission the program's services declare; a whole group is written as `adm.storage.*`, everything as `*`. A build fails when the code reaches more.", Field.Kind.Permissions),
			),
		)
	}

	/** Sections a checkbox switches on; unchecking one removes its keys on save. */
	private val optionalGroups = mapOf("Updates" to "update.", "Package" to "package.")
	private val groupSwitches = LinkedHashMap<String, JBCheckBox>()

	private fun sectionIcon(unit: ADMUnit, title: String): Icon = when (title) {
		kindTitle(unit) -> unit.kind.icon
		"Publisher" -> AllIcons.General.User
		"Updates" -> AllIcons.Actions.Download
		"Package" -> AllIcons.Nodes.Artifact
		"Policy" -> AllIcons.Nodes.Padlock
		"Dependencies" -> AllIcons.Nodes.PpLib
		"Registries" -> AllIcons.Nodes.PpWeb
		RAW -> AllIcons.FileTypes.Config
		else -> AllIcons.Nodes.Folder
	}

	private fun buildForm(unit: ADMUnit, info: JsonObject?, error: String?, packages: List<JsonObject> = emptyList()) {
		val previous = sections.selectedId
		sections.clear()
		fields.clear()
		writes.clear()
		groupSwitches.clear()
		val values = LinkedHashMap<String, String>()
		if (info != null) flatten("", info, values)
		val a = "apps." + unit.name
		for (k in values.keys.toList()) {
			if (k.startsWith("update.") || k.startsWith("package.")) values.putIfAbsent("$a.$k", values[k]!!)
		}
		values["publisher.repoSalt"]?.let { values["publisher.repoSalt"] = if (it == "true") "present" else "missing" }
		values.putIfAbsent("resolved.appName", unit.name)
		values.putIfAbsent("$a.version", unit.version ?: "")
		if (unit.kind != ADMUnit.Kind.Application) values["$a.version"] = unit.version ?: values["$a.version"] ?: ""
		loaded = values
		val hasManifest = unit.manifest != null
		val sandbox = JBCheckBox("Apply the OS sandbox at startup", values["policy.sandbox"] == "true")
		fields["policy.sandbox"] = sandbox
		writes["policy.sandbox"] = "policy.sandbox"
		val appTable = info?.obj("apps")?.obj(unit.name)
		val fileRules = appTable?.obj("package")?.arr("files") ?: info?.obj("package")?.arr("files")
		val filesEditor = FilesEditor(project, unit.projectDir, fileRules)
		files = filesEditor
		val public = info?.obj("publicRegistry")?.let { o -> o.str("name")?.let { n -> Registry(n, o.str("url") ?: "", "") } }
		val reg = RegistriesEditor(info?.obj("registries"), info?.arr("registryOrder").strings(), public)
		registries = reg
		val ranges = info?.obj("deps")?.entrySet()?.associate { (k, v) -> k to (v.takeIf { it.isJsonPrimitive }?.asString ?: "") }.orEmpty()
		val locked = packages.mapNotNull { p -> p.str("library")?.let { it to p } }.toMap()
		val deps = (ranges.keys + locked.keys).distinct().sorted().map { lib ->
			val p = locked[lib]
			val parts = arrayListOf(lib)
			p?.str("version")?.takeIf { it.isNotEmpty() }?.let { parts += it }
			ranges[lib]?.takeIf { it.isNotEmpty() }?.let { parts += "(range $it)" }
			p?.str("state")?.takeIf { it.isNotEmpty() && it != "installed" }?.let { parts += it }
			if (p == null) parts += "not installed"
			parts.joinToString("  ")
		}

		fun addPage(title: String, page: JComponent) = sections.add(title, sectionIcon(unit, title), page)

		var first = true
		for ((title, spec) in layout(unit)) {
			if (!hasManifest && title !in setOf(kindTitle(unit), "Updates", "Package")) continue
			// A library or plugin has no distribution archive, but its
			// `[package] files` ship inside the .admlib / .admplugin: the
			// Package page shows just the file list for it.
			val libraryPackage = title == "Package" && unit.kind != ADMUnit.Kind.Application
			if (title in optionalGroups && unit.kind != ADMUnit.Kind.Application && !libraryPackage) continue
			val prefix = if (libraryPackage) null else optionalGroups[title]
			val fieldsSpec = if (libraryPackage) emptyList() else spec
			val configured = prefix == null || spec.any { f -> f.write != null && !values[f.read].isNullOrEmpty() }
			val showError = first && error != null && info == null
			first = false
			val fieldsOf: com.intellij.ui.dsl.builder.Panel.() -> Unit = {
				for (f in fieldsSpec) {
					val value = values[f.read] ?: ""
					if (f.write == null && value.isEmpty()) continue
					row("${f.label}:") {
						if (f.write == null) {
							label(value).applyToComponent { if (f.bold) font = JBUI.Fonts.label().asBold() }.comment(f.hint)
						} else {
							val control: JComponent = when (f.kind) {
								Field.Kind.Text -> JBTextField(value)
								Field.Kind.Pick -> ADMMultiPickField(f.options, value)
								Field.Kind.Choice -> ComboBox(f.options.toTypedArray()).apply { selectedItem = f.options.firstOrNull { it == value } ?: f.options.firstOrNull() }
								Field.Kind.Permissions -> ADMPermissionPickField(value) { done ->
									ADMCli.async(project, listOf("audit", "--catalog", "--json", "--dir", unit.projectDir), unit.projectDir) { r ->
										done(r.json().asArrayOrNull().objects().mapNotNull { o -> o.str("permission")?.let { it to (o.str("description") ?: "") } }, if (r.ok) null else r.failure())
									}
								}
								Field.Kind.Version -> ADMVersionField(value)
								Field.Kind.Folder -> TextFieldWithBrowseButton().apply {
									text = value
									addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(f.label))
								}
								Field.Kind.File -> TextFieldWithBrowseButton().apply {
									text = value
									addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor().withTitle(f.label))
								}
							}
							fields[f.read] = control
							writes[f.read] = f.write
							cell(control).align(Align.FILL).comment(f.hint)
						}
					}
				}
				if (title == "Publisher") {
					val exists = values["publisher.privateKeyExists"]
					if (exists == "false") row { label("The private key is not on this machine: signing fails until it is restored or the keys are regenerated.").applyToComponent { icon = AllIcons.General.Warning } }
					row {
						button("Regenerate keys…") { rekey() }
					}.rowComment("Makes a new key pair and repo salt. Every application id changes, consumers of published packages must opt in to the new key, and the old private key stops mattering.")
				}
				if (title == "Package") {
					row("Files:") { cell(filesEditor.component).align(Align.FILL) }
						.rowComment(if (libraryPackage) "Shipped inside the package under files/: headers and C sources a binding needs, assets. A program that installs the library gets the folder on its include path and the C sources compiled. Source path, destination, optional mode." else "What the release archive carries besides the executable: source path, destination inside the install root, optional mode.")
				}
				if (title == "Policy") {
					row { cell(sandbox) }.rowComment("`[policy] sandbox = true`: Landlock and seccomp on Linux, from the compiled policy table.")
				}
			}
			val page = panel {
				if (showError) row { comment(error!!) }
				if (prefix != null) {
					lateinit var switch: com.intellij.ui.dsl.builder.Cell<JBCheckBox>
					row {
						switch = checkBox("Configure ${title.lowercase()}").applyToComponent { isSelected = configured; font = JBUI.Fonts.label().asBold() }
						groupSwitches[title] = switch.component
					}
					indent(fieldsOf).visibleIf(switch.selected)
				} else {
					fieldsOf()
				}
			}
			page.border = JBUI.Borders.empty(12, 16, 16, 16)
			addPage(title, page)
		}
		if (hasManifest) {
			addPage("Dependencies", panel {
				if (deps.isEmpty()) row { comment("No libraries yet. The Libraries tab installs them and records the range here.") }
				for (d in deps) row { label(d) }
				if (deps.isNotEmpty()) row { comment("From adm.lock and the [deps] ranges of adm.toml; the Libraries tab installs, updates and removes them.") }
			}.apply { border = JBUI.Borders.empty(12, 16, 16, 16) })
			addPage("Registries", panel {
				row { cell(reg.component).align(Align.FILL) }
					.rowComment("Where libraries are searched and published, in resolution order; the public registry is used when none is listed.")
			}.apply { border = JBUI.Borders.empty(12, 16, 16, 16) })
			sections.add(RAW, sectionIcon(unit, RAW), rawHost, scroll = false)
		}
		sections.select(previous)
	}

	/** Flattens nested JSON into `a.b.c` keys; arrays of primitives join with commas. */
	private fun flatten(prefix: String, o: JsonObject, out: MutableMap<String, String>) {
		for ((k, v) in o.entrySet()) {
			val key = if (prefix.isEmpty()) k else "$prefix.$k"
			when {
				v.isJsonObject -> flatten(key, v.asJsonObject, out)
				v.isJsonArray -> out[key] = v.asJsonArray.filter { it.isJsonPrimitive }.joinToString(", ") { it.asString }
				v.isJsonPrimitive -> out[key] = v.asString
				else -> out[key] = ""
			}
		}
	}

	// --- save ---------------------------------------------------------------

	private fun save() {
		val unit = unit ?: return
		if (sections.selectedId == RAW) {
			editor?.document?.let { doc -> WriteAction.run<RuntimeException> { FileDocumentManager.getInstance().saveDocument(doc) } }
			return
		}
		val dir = unit.projectDir
		val steps = ArrayList<List<String>>()
		if (unit.manifest == null) steps.add(listOf("app", "init", "--dir", unit.dir))
		val off = groupSwitches.filter { !it.value.isSelected }.keys.mapNotNull { optionalGroups[it] }
		for ((read, c) in fields) {
			val key = writes[read] ?: continue
			if (off.any { key.startsWith(it) }) {
				if (!loaded[read].isNullOrEmpty()) steps.add(listOf("app", "unset", "--dir", dir, "--app", unit.name, key))
				continue
			}
			val now = when (c) {
				is JBTextField -> c.text.trim()
				is TextFieldWithBrowseButton -> c.text.trim()
				is ADMMultiPickField -> c.text
				is ADMPermissionPickField -> c.text
				is ComboBox<*> -> c.selectedItem?.toString()?.trim().orEmpty()
				is ADMVersionField -> c.value
				is JBCheckBox -> if (c.isSelected) "true" else "false"
				else -> continue
			}
			val before = loaded[read] ?: if (c is JBCheckBox) "false" else ""
			if (now == before) continue
			steps.add(
				if (now.isEmpty() && c !is JBCheckBox) listOf("app", "unset", "--dir", dir, "--app", unit.name, key)
				else listOf("app", "set", "--dir", dir, "--app", unit.name, key, now)
			)
		}
		files?.change(dir, if (unit.kind == ADMUnit.Kind.Application) unit.name else "")?.let(steps::add)
		registries?.changes(dir)?.let(steps::addAll)
		if (steps.isEmpty()) return
		saving = true
		ApplicationManager.getApplication().executeOnPooledThread {
			var failure: String? = null
			for (args in steps) {
				val r = ADMCli.run(project, args, dir)
				if (!r.ok) {
					failure = "adm ${args.take(2).joinToString(" ")} ${args.getOrNull(args.size - 2) ?: ""}: ${r.failure()}"
					break
				}
			}
			ApplicationManager.getApplication().invokeLater({
				saving = false
				if (failure != null) notify(failure, NotificationType.ERROR)
				else {
					LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(dir))?.refresh(false, false)
					ADMWorkspace.getInstance(project).refresh()
				}
			}, project.disposed)
		}
	}

	/** New key pair and salt through `adm app rekey`, after a confirmation, since every id changes. */
	private fun rekey() {
		val unit = unit ?: return
		val answer = Messages.showOkCancelDialog(
			project,
			"Generate a new signing key and repo salt for ${ADMPanelActions.relative(project, unit.projectDir)}?\n\nEvery application id derived from them changes, and packages already published are signed by the old key.",
			"Regenerate Publisher Keys",
			"Regenerate",
			Messages.getCancelButton(),
			Messages.getWarningIcon(),
		)
		if (answer != Messages.OK) return
		ADMCli.background(project, "Regenerating publisher keys", listOf("app", "rekey", "--dir", unit.projectDir), unit.projectDir) { r ->
			if (r.ok) {
				notify("New publisher keys written; the private key path is in the Run output", NotificationType.INFORMATION)
				bind(unit)
			} else notify("adm app rekey failed: ${r.failure()}", NotificationType.ERROR)
		}
	}

	private fun createManifest() {
		val unit = unit ?: return
		ADMCli.background(project, "Creating adm.toml", listOf("app", "init", "--dir", unit.dir), unit.dir) { r ->
			if (r.ok) ADMWorkspace.getInstance(project).refresh() else notify("adm app init failed: ${r.failure()}", NotificationType.ERROR)
		}
	}

	private fun notify(text: String, type: NotificationType) {
		NotificationGroupManager.getInstance().getNotificationGroup("ADM").createNotification(text, type).notify(project)
	}

	override fun dispose() = releaseEditor()

	private companion object {
		/** The section that shows the manifest file itself. */
		const val RAW = "adm.toml"
		/** The `compat.os` choices: the target operating systems the compiler knows (ADM_OS). */
		/** What `adm package build` can produce today. */
		private val PACKAGE_FORMATS = listOf("tar.gz")
		val PLATFORMS = listOf("linux", "darwin", "windows", "android", "ios", "wasm")
		/** The `compat.arch` choices: the target architectures the compiler knows (ADM_ARCH). */
		val ARCHITECTURES = listOf("amd64", "arm64", "386", "arm", "riscv64", "ppc64le", "wasm32")
	}

	// --- package files --------------------------------------------------------

	/**
	 * The `[[package.files]]` rules as an editable from / to / mode table with
	 * +/-; saved as one `adm app set package.files "from=>to:mode, …"`.
	 */
	private class FilesEditor(private val project: Project, private val root: String, rules: JsonArray?) {
		private val model = DefaultTableModel(arrayOf("From", "To", "Mode"), 0)
		private val before: String
		val component: JComponent

		init {
			for (el in rules ?: JsonArray()) {
				val o = el as? JsonObject ?: continue
				model.addRow(arrayOf(o.str("From") ?: o.str("from") ?: "", o.str("To") ?: o.str("to") ?: "", o.str("Mode") ?: o.str("mode") ?: ""))
			}
			before = encode()
			val table = JBTable(model).apply {
				setShowGrid(false)
				rowHeight = JBUI.scale(24)
				emptyText.text = "No files besides the executable"
				emptyText.appendSecondaryText("Add a file…", SimpleTextAttributes.LINK_ATTRIBUTES) { addRow() }
			}
			val decorated = ToolbarDecorator.createDecorator(table)
				.setAddAction { addRow() }
				.setRemoveAction { table.selectedRows.sortedDescending().forEach(model::removeRow) }
				.disableUpDownActions()
				.createPanel()
			decorated.preferredSize = Dimension(JBUI.scale(500), JBUI.scale(130))
			component = decorated
		}

		/** Picks files or folders under the project and adds a rule per pick: from the relative path, to its name. */
		private fun addRow() {
			val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor().withTitle("Files to Package")
			val base = LocalFileSystem.getInstance().findFileByIoFile(File(root))
			val picked = FileChooser.chooseFiles(descriptor, project, base)
			if (picked.isEmpty()) {
				model.addRow(arrayOf("", "", ""))
				return
			}
			val rootPath = File(root).absolutePath.replace('\\', '/').trimEnd('/') + "/"
			for (vf in picked) {
				val abs = vf.path
				val from = if (abs.startsWith(rootPath)) abs.removePrefix(rootPath) else abs
				model.addRow(arrayOf(from, vf.name, ""))
			}
		}

		/** `from=>to:mode` per row, comma-separated; the value `adm app set` takes. */
		private fun encode(): String = (0 until model.rowCount).mapNotNull { r ->
			val from = model.getValueAt(r, 0)?.toString()?.trim().orEmpty()
			val to = model.getValueAt(r, 1)?.toString()?.trim().orEmpty()
			val mode = model.getValueAt(r, 2)?.toString()?.trim().orEmpty()
			if (from.isEmpty()) null else "$from=>${to.ifEmpty { from }}" + (if (mode.isEmpty()) "" else ":$mode")
		}.joinToString(", ")

		/** The command that stores the edited list, or null when nothing changed. */
		fun change(dir: String, app: String): List<String>? {
			val now = encode()
			if (now == before) return null
			// An application's rules live under its [apps.<Name>.package] table; a
			// library's or plugin's under the file-level [package] the build reads.
			val target = if (app.isEmpty()) emptyList() else listOf("--app", app)
			return if (now.isEmpty()) listOf("app", "unset", "--dir", dir) + target + "package.files"
			else listOf("app", "set", "--dir", dir) + target + listOf("package.files", now)
		}

	}

	// --- registries -------------------------------------------------------------

	/** One `[registries.<name>]` table. */
	private class Registry(var name: String, var url: String, var token: String)

	/**
	 * The registries as a master list with +/- and the selected one's fields on
	 * the right, like the toolchain editor in Settings. Saving diffs against
	 * what was loaded and emits `adm app set` / `adm app unset` calls.
	 */
	private class RegistriesEditor(loaded: JsonObject?, order: List<String>, private val public: Registry?) {
		private val original = LinkedHashMap<String, Registry>()
		private val model = DefaultListModel<Registry>()
		private val list = JBList(model)
		private val nameField = JBTextField()
		private val urlField = JBTextField()
		private val tokenField = JBTextField()
		private var current: Registry? = null
		private val split = OnePixelSplitter(false, 0.4f)
		val component: JComponent

		init {
			val names = (order + loaded?.keySet().orEmpty()).distinct()
			for (n in names) {
				val o = loaded?.let { if (it.has(n)) it.obj(n) else null }
				val r = Registry(n, o?.get("url")?.takeIf { it.isJsonPrimitive }?.asString ?: "", o?.get("token")?.takeIf { it.isJsonPrimitive }?.asString ?: "")
				original[n] = Registry(r.name, r.url, r.token)
				model.addElement(r)
			}
			// With no [registries] the public one is what adm searches: show
			// it, so others can be placed before or after it, or it dropped.
			// It only reaches the file once the list holds something else.
			if (names.isEmpty() && public != null) model.addElement(Registry(public.name, public.url, ""))
			list.cellRenderer = object : ColoredListCellRenderer<Registry>() {
				override fun customizeCellRenderer(l: JList<out Registry>, value: Registry?, index: Int, selected: Boolean, hasFocus: Boolean) {
					value ?: return
					icon = AllIcons.Nodes.PpWeb
					append(value.name.ifEmpty { "unnamed" })
					if (public != null && value.name == public.name && value.url == public.url) append("  official", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
					if (value.url.isNotEmpty()) append("  ${value.url}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
				}
			}
			list.emptyText.text = "No registries"
			list.emptyText.appendSecondaryText("Add a registry…", SimpleTextAttributes.LINK_ATTRIBUTES) { addRegistry() }
			list.addListSelectionListener { if (!it.valueIsAdjusting) select(list.selectedValue) }
			val decorated = ToolbarDecorator.createDecorator(list)
				.setAddAction { addRegistry() }
				.setRemoveAction { list.selectedIndex.takeIf { it >= 0 }?.let { model.remove(it); select(list.selectedValue) } }
				.setMoveUpAction { move(-1) }
				.setMoveDownAction { move(1) }
				.setPanelBorder(JBUI.Borders.empty())
				.createPanel()

			val form = panel {
				row("Name:") { cell(nameField).align(Align.FILL).comment("How adm.toml and `adm publish --registry` refer to it.") }
				row("URL:") { cell(urlField).align(Align.FILL).comment("Git URL of the registry repository (ssh or https).") }
				row("Token:") { cell(tokenField).align(Align.FILL).comment("An `\${ENV_VAR}` placeholder for the HTTPS calls that need one; never a literal secret.") }
			}
			form.border = JBUI.Borders.empty(8, 12)
			val listener = object : DocumentListener {
				override fun insertUpdate(e: DocumentEvent) = push()
				override fun removeUpdate(e: DocumentEvent) = push()
				override fun changedUpdate(e: DocumentEvent) = push()
			}
			for (f in listOf(nameField, urlField, tokenField)) f.document.addDocumentListener(listener)
			split.firstComponent = decorated
			split.secondComponent = form
			form.isVisible = false
			val box = JPanel(BorderLayout()).apply {
				border = JBUI.Borders.customLine(JBUI.CurrentTheme.ToolWindow.borderColor(), 1)
				add(split, BorderLayout.CENTER)
				preferredSize = Dimension(JBUI.scale(600), JBUI.scale(190))
			}
			component = box
			select(null)
		}


		private fun addRegistry() {
			model.addElement(Registry("", "", ""))
			list.selectedIndex = model.size() - 1
			nameField.requestFocus()
		}

		private fun move(delta: Int) {
			val i = list.selectedIndex
			val j = i + delta
			if (i < 0 || j < 0 || j >= model.size()) return
			val r = model.remove(i)
			model.add(j, r)
			list.selectedIndex = j
		}

		private fun select(r: Registry?) {
			current = null
			nameField.text = r?.name ?: ""
			urlField.text = r?.url ?: ""
			tokenField.text = r?.token ?: ""
			split.secondComponent?.isVisible = r != null
			split.revalidate()
			split.repaint()
			current = r
		}

		private fun push() {
			val r = current ?: return
			r.name = nameField.text.trim()
			r.url = urlField.text.trim()
			r.token = tokenField.text.trim()
			list.repaint()
		}

		/** The adm commands that turn the loaded registries into the edited ones. */
		fun changes(dir: String): List<List<String>> {
			val out = ArrayList<List<String>>()
			val now = (0 until model.size()).map { model.get(it) }.filter { it.name.isNotEmpty() }
			// Only the implied public registry, as loaded: nothing to write.
			if (original.isEmpty() && public != null && now.size == 1 && now[0].name == public.name && now[0].url == public.url && now[0].token.isEmpty()) return out
			for (name in original.keys) if (now.none { it.name == name }) out.add(listOf("app", "unset", "--dir", dir, "registries.$name"))
			for (r in now) {
				val before = original[r.name]
				if (before == null || before.url != r.url) out.add(listOf("app", "set", "--dir", dir, "registries.${r.name}.url", r.url))
				if ((before?.token ?: "") != r.token) {
					out.add(if (r.token.isEmpty()) listOf("app", "unset", "--dir", dir, "registries.${r.name}.token") else listOf("app", "set", "--dir", dir, "registries.${r.name}.token", r.token))
				}
			}
			val orderNow = now.map { it.name }
			if (orderNow != original.keys.toList() && orderNow.isNotEmpty()) out.add(listOf("app", "set", "--dir", dir, "registries.order", orderNow.joinToString(", ")))
			return out
		}
	}
}
