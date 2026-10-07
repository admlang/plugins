package org.adm.intellij.ide

import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.LocalPathCellEditor
import com.intellij.util.ui.UIUtil
import org.adm.intellij.lsp.ADMServerRefresh
import org.adm.intellij.run.ADMExec
import org.adm.intellij.settings.ADMSettingsConfigurable
import org.adm.intellij.settings.ADMSettingsState
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor

/**
 * The Toolchain tab: the compiler that is installed and whether a newer one
 * exists, what `adm doctor` finds about the machine (each check with a way to
 * fix it), the ADM_* environment the plugin hands to every adm process, and
 * the build cache with a way to empty it.
 */
class ADMToolchainPanel(private val project: Project, parentDisposable: Disposable) : JPanel(BorderLayout()), Disposable {
	private val versionLabel = bold("…")
	private val pathLabel = bold("…")
	private val buildLabel = bold("…")
	private val updateLabel = JBLabel("")
	private val cachePathLabel = JBLabel(ADMExec.defaultCacheDir().ifBlank { "-" })
	private val updateButton = JButton("Update")
	private val doctorPanel = JPanel(GridBagLayout())
	private val cacheLabel = JBLabel("…")
	private val cleanButton = JButton("Clean")
	private val envModel = EnvModel()
	/** When [refresh] last ran, so re-showing the tab does not re-run it within seconds. */
	private var lastRefresh = 0L

	init {
		Disposer.register(parentDisposable, this)
		updateButton.isVisible = false
		updateButton.addActionListener { update() }
		cleanButton.addActionListener { clean() }

		val envTable = EnvTable(envModel)
		val margin = JBUI.Borders.empty(12, 16, 16, 16)
		val sections = ADMSectionsView("ADM.Toolchain.sections")
		sections.add("Compiler", AllIcons.Nodes.Console, panel {
			row("Version:") { cell(versionLabel) }
			row("Executable:") { cell(pathLabel) }
			row("Build:") { cell(buildLabel) }
			row("") {
				cell(updateLabel)
				cell(updateButton)
				cell(ActionLink("Check again") { refresh() })
			}
		}.apply { border = margin })
		sections.add("Doctor", AllIcons.General.InspectionsEye, panel {
			row { cell(doctorPanel).align(Align.FILL) }
		}.apply { border = margin })
		sections.add("Environment", AllIcons.Nodes.Variable, panel {
			row { cell(envTable.withHeader()).align(Align.FILL) }
				.rowComment("Handed to every adm process the IDE starts: the language server, builds, runs and tests. Blank means the compiler's default. Double-click a value to edit it.")
		}.apply { border = margin })
		sections.add("Cache", AllIcons.Nodes.DataTables, panel {
			row("Path:") { cell(cachePathLabel) }
			row("Size:") { cell(cacheLabel) }
			row { cell(cleanButton) }
		}.apply { border = margin })
		sections.select(null)
		add(sections, BorderLayout.CENTER)
		// The tab lives as long as the tool window; a fix made outside (a
		// package installed, a setting changed) shows up when it is next seen.
		addAncestorListener(object : javax.swing.event.AncestorListener {
			override fun ancestorAdded(e: javax.swing.event.AncestorEvent) {
				if (System.currentTimeMillis() - lastRefresh > 3_000) refresh()
			}
			override fun ancestorRemoved(e: javax.swing.event.AncestorEvent) {}
			override fun ancestorMoved(e: javax.swing.event.AncestorEvent) {}
		})
		refresh()
	}

	/** Re-reads version, doctor and cache size. */
	fun refresh() {
		lastRefresh = System.currentTimeMillis()
		pathLabel.text = ADMExec.resolve(project.basePath)?.toString() ?: "not found"
		updateLabel.icon = AnimatedIcon.Default()
		updateLabel.text = "Checking…"
		updateButton.isVisible = false
		ADMCli.async(project, listOf("version", "--json")) { r ->
			val o = r.json().asObjectOrNull()
			if (o == null) {
				versionLabel.text = r.failure()
				updateLabel.icon = AllIcons.General.Warning
				updateLabel.text = "Could not check for updates."
				return@async
			}
			versionLabel.text = o.str("version") ?: "?"
			val build = listOfNotNull(o.str("buildDate"), o.str("commitHash")?.take(10)).joinToString("  ")
			buildLabel.text = build.ifEmpty { "-" }
			val newer = o.get("updateAvailable")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
			updateLabel.text = if (newer) "A newer release is available." else "Up to date."
			updateLabel.icon = if (newer) AllIcons.Actions.Download else AllIcons.General.InspectionsOK
			updateButton.isVisible = newer
		}
		doctorPanel.removeAll()
		doctorPanel.add(JBLabel("Checking…", AnimatedIcon.Default(), JBLabel.LEFT), gbc(0, 0, 4))
		doctorPanel.revalidate()
		doctorPanel.repaint()
		ADMCli.async(project, listOf("doctor", "--json")) { r -> showDoctor(r) }
		cachePathLabel.text = ADMExec.defaultCacheDir().ifBlank { "-" }
		measureCache()
	}

	// --- doctor -------------------------------------------------------------

	/** One row per check: status icon, heading, what was found, and a fix when it failed. */
	private fun showDoctor(r: ADMCli.Result) {
		doctorPanel.removeAll()
		val results = r.json().asObjectOrNull()?.arr("results")
		if (results == null) {
			doctorPanel.add(JBLabel(r.failure()).apply { foreground = UIUtil.getContextHelpForeground() }, gbc(0, 0, 4))
		} else {
			var row = 0
			for (el in results) {
				val o = el as? JsonObject ?: continue
				val name = o.str("name") ?: ""
				val status = o.str("status")?.lowercase() ?: ""
				val ok = status == "ok" || status == "pass"
				val icon = when {
					ok -> AllIcons.General.InspectionsOK
					status.startsWith("warn") -> AllIcons.General.Warning
					else -> AllIcons.General.Error
				}
				val title = JBLabel(o.str("title") ?: name, icon, JBLabel.LEFT).apply { font = JBUI.Fonts.label().asBold() }
				val message = JBLabel(o.str("message") ?: "").apply {
					foreground = if (ok) UIUtil.getLabelForeground() else JBColor.namedColor("Label.errorForeground", JBColor.RED)
				}
				val details = JBLabel(o.str("details") ?: "").apply {
					font = JBUI.Fonts.smallFont()
					isVisible = text.isNotEmpty()
				}
				doctorPanel.add(title, gbc(0, row, 1).apply { insets = JBUI.insets(8, 0, 0, 16) })
				doctorPanel.add(message, gbc(1, row, 1).apply { weightx = 1.0; insets = JBUI.insets(8, 0, 0, 16) })
				fixFor(name, ok, o.str("fixHint"))?.let { doctorPanel.add(it, gbc(2, row, 1).apply { insets = JBUI.insets(8, 0, 0, 0) }) }
				row++
				if (details.isVisible) {
					doctorPanel.add(details, gbc(1, row, 2).apply { weightx = 1.0; insets = JBUI.insets(2, 0, 2, 0) })
					row++
				}
			}
		}
		doctorPanel.revalidate()
		doctorPanel.repaint()
	}

	/**
	 * What the user can do about a failed check: the doctor's fix hint, and
	 * beside it a link that opens the download page of a missing tool, the
	 * plugin settings for an ADM_LIB style hint, or the toolchain update.
	 */
	private fun fixFor(check: String, ok: Boolean, hint: String?): javax.swing.JComponent? {
		val text = hint?.trim().orEmpty()
		val lower = text.lowercase()
		val link = when {
			check == "env" -> settingsLink()
			ok -> null
			check == "clang" || "clang" in lower -> ActionLink("How to install") { BrowserUtil.browse("https://releases.llvm.org/") }
			check == "git" || lower.startsWith("install git") -> ActionLink("How to install") { BrowserUtil.browse("https://git-scm.com/downloads") }
			check == "cuda" -> ActionLink("How to install") { BrowserUtil.browse("https://developer.nvidia.com/cuda-downloads") }
			"adm_lib" in lower || "adm_cache" in lower -> settingsLink()
			check == "stdlib" || check == "backend" -> ActionLink("Update toolchain") { update() }
			else -> null
		}
		val hintLabel = text.takeIf { !ok && it.isNotEmpty() }?.let { JBLabel(it).apply { foreground = UIUtil.getContextHelpForeground() } }
		if (hintLabel == null) return link
		if (link == null) return hintLabel
		return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
			isOpaque = false
			add(hintLabel)
			add(JBLabel("  "))
			add(link)
		}
	}

	private fun settingsLink() = ActionLink("Open settings") { ShowSettingsUtil.getInstance().showSettingsDialog(project, ADMSettingsConfigurable::class.java) }

	private fun bold(text: String) = JBLabel(text).apply { font = JBUI.Fonts.label().asBold() }

	private fun gbc(x: Int, y: Int, width: Int): GridBagConstraints = GridBagConstraints().apply {
		gridx = x
		gridy = y
		gridwidth = width
		anchor = GridBagConstraints.WEST
		fill = GridBagConstraints.HORIZONTAL
	}

	// --- cache and update ---------------------------------------------------

	private fun measureCache() {
		val dir = ADMExec.defaultCacheDir().takeIf { it.isNotBlank() }?.let { Path.of(it) }
		if (dir == null || !Files.isDirectory(dir)) {
			cacheLabel.text = "empty"
			return
		}
		ApplicationManager.getApplication().executeOnPooledThread {
			var bytes = 0L
			var files = 0L
			runCatching {
				Files.walk(dir).use { stream ->
					stream.filter { Files.isRegularFile(it) }.forEach { bytes += runCatching { Files.size(it) }.getOrDefault(0L); files++ }
				}
			}
			ApplicationManager.getApplication().invokeLater({
				cacheLabel.text = "${human(bytes)} in $files files"
			}, project.disposed)
		}
	}

	private fun update() {
		updateButton.isEnabled = false
		ADMCli.background(project, "Updating the ADM toolchain", listOf("update")) { r ->
			updateButton.isEnabled = true
			if (r.ok) {
				notify("ADM toolchain updated", NotificationType.INFORMATION)
				ADMServerRefresh.restartDaemon(project)
			} else {
				notify("ADM update failed: ${r.failure()}", NotificationType.ERROR)
			}
			refresh()
		}
	}

	private fun clean() {
		val answer = Messages.showOkCancelDialog(
			project,
			"Remove every cached object and native support file? The next build recompiles them.",
			"Clean the ADM Cache",
			"Clean",
			Messages.getCancelButton(),
			Messages.getQuestionIcon(),
		)
		if (answer != Messages.OK) return
		cleanButton.isEnabled = false
		ADMCli.background(project, "Cleaning the ADM cache", listOf("clean")) { r ->
			cleanButton.isEnabled = true
			if (!r.ok) notify("adm clean failed: ${r.failure()}", NotificationType.ERROR)
			measureCache()
		}
	}

	private fun notify(text: String, type: NotificationType) {
		NotificationGroupManager.getInstance().getNotificationGroup("ADM").createNotification(text, type).notify(project)
	}

	private fun human(bytes: Long): String {
		val units = arrayOf("B", "KB", "MB", "GB", "TB")
		var v = bytes.toDouble()
		var i = 0
		while (v >= 1000 && i < units.size - 1) {
			v /= 1000
			i++
		}
		return if (i == 0) "$bytes B" else String.format("%.1f %s", v, units[i])
	}


	override fun dispose() {}

	// --- environment ----------------------------------------------------------

	/** One ADM_* variable: its meaning and how its value is edited. */
	private class Variable(val name: String, val meaning: String, val choices: List<String> = emptyList(), val path: Boolean = false)

	/**
	 * The ADM_* variables the compiler and its programs read, with the value
	 * the plugin sets. Editing the value column stores it in the settings.
	 */
	private class EnvModel : DefaultTableModel(arrayOf("Variable", "Value", "Meaning"), 0) {
		val known = listOf(
			Variable("ADM_LIB", "Prelude and standard library directory", path = true),
			Variable("ADM_PKG", "Package cache for installed libraries", path = true),
			Variable("ADM_CATALOG", "Registry clones adm search/get/publish read", path = true),
			Variable("ADM_CLANG", "C compiler driver to invoke", path = true),
			Variable("ADM_OS", "Target OS (default: host)", listOf("", "linux", "darwin", "windows", "android", "ios", "wasm")),
			Variable("ADM_ARCH", "Target architecture (default: host)", listOf("", "amd64", "arm64", "386", "arm", "riscv64", "ppc64le", "wasm32")),
			Variable("ADM_NVCC", "nvcc for cuda def bodies; `none` builds without PTX", path = true),
			Variable("ADM_NVCC_FLAGS", "Extra flags for every nvcc call"),
			Variable("ADM_CUDA", "Kernel launch path", listOf("", "gpu", "cpu")),
			Variable("ADM_CUDA_DEVICE", "CUDA device ordinal"),
			Variable("ADM_VERIFY_IR", "Run the MIR verifier after IRGen", listOf("", "1")),
			Variable("ADM_NO_OBJ_CACHE", "Bypass the LLVM object cache", listOf("", "1")),
			Variable("ADM_NO_NATIVE_CACHE", "Bypass the native-support cache", listOf("", "1")),
			Variable("ADM_PHASE_TIMING", "File to append per-phase build timings to", path = true),
			Variable("ADM_LLVM_DUMP_IR", "Directory to write each module's LLVM IR into", path = true),
			Variable("ADM_TASK_STACK", "Per-task stack reservation of compiled programs (default 32M)"),
			Variable("ADM_PROCS", "Parallelism bound of compiled programs (default: CPU count)"),
			Variable("ADM_SCHED_DEBUG", "Trace scheduling decisions to stderr", listOf("", "1")),
			Variable("ADM_MEM_STATS", "Print allocator statistics every 200k allocations", listOf("", "1")),
			Variable("ADM_MEM_TRACE", "File to record every allocation and free into", path = true),
			Variable("ADM_SANDBOX", "Apply the OS sandbox from the compiled policy table", listOf("", "1", "0")),
			Variable("ADM_APP_HOME", "Overrides the application's per-user home folder", path = true),
			Variable("ADM_DIST_REPO", "GitHub owner/name whose releases carry the toolchain"),
		)

		init {
			val stored = ADMSettingsState.getInstance().extraEnv
			for (v in known) addRow(arrayOf(v.name, stored[v.name] ?: "", v.meaning))
			for ((name, value) in stored) if (known.none { it.name == name }) addRow(arrayOf(name, value, ""))
		}

		fun variable(row: Int): Variable? = known.firstOrNull { it.name == getValueAt(row, 0)?.toString() }

		override fun isCellEditable(row: Int, column: Int): Boolean = column == 1

		override fun setValueAt(value: Any?, row: Int, column: Int) {
			super.setValueAt(value, row, column)
			if (column != 1) return
			val name = getValueAt(row, 0)?.toString() ?: return
			val text = value?.toString()?.trim().orEmpty()
			val env = ADMSettingsState.getInstance().extraEnv
			if (text.isEmpty()) env.remove(name) else env[name] = text
		}
	}

	/** The variable table: a dropdown for enumerated values, a browse button for paths. */
	private inner class EnvTable(private val model: EnvModel) : JBTable(model) {
		init {
			setShowGrid(false)
			rowHeight = JBUI.scale(24)
			columnModel.getColumn(0).preferredWidth = JBUI.scale(180)
			columnModel.getColumn(1).preferredWidth = JBUI.scale(260)
			columnModel.getColumn(2).preferredWidth = JBUI.scale(480)
			preferredScrollableViewportSize = java.awt.Dimension(JBUI.scale(600), rowHeight * model.rowCount)
			emptyText.text = "No environment variables"
		}

		override fun getCellEditor(row: Int, column: Int): TableCellEditor {
			val v = model.variable(row)
			return when {
				column == 1 && v != null && v.choices.isNotEmpty() -> DefaultCellEditor(ComboBox(v.choices.toTypedArray()).apply { isEditable = true })
				column == 1 && v != null && v.path -> LocalPathCellEditor(project)
				else -> super.getCellEditor(row, column)
			}
		}

		/** The table with its header, sized to its rows so the whole tab scrolls rather than the table. */
		fun withHeader(): JPanel = JPanel(BorderLayout()).apply {
			add(tableHeader, BorderLayout.NORTH)
			add(this@EnvTable, BorderLayout.CENTER)
			border = JBUI.Borders.customLine(JBUI.CurrentTheme.ToolWindow.borderColor(), 1)
		}
	}
}
