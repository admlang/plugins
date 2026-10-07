package org.adm.intellij.ide

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.EventDispatcher
import java.util.EventListener
import javax.swing.Icon
import org.adm.intellij.ADMIcons

/**
 * One compilation unit the workspace declares: an `application`, `library`
 * or `plugin` block. The rows of the tool window's project dropdown.
 */
data class ADMUnit(
	val kind: Kind,
	val name: String,
	/** Absolute path of the file holding the declaration. */
	val file: String,
	val line: Int,
	/** Directory the unit's commands run from (`adm run NAME --path DIR`). */
	val dir: String,
	/** The nearest `adm.toml` walking up from [dir], or null when there is none. */
	val manifest: String?,
	val version: String?,
) {
	enum class Kind(val label: String, val icon: Icon) {
		Application("application", ADMIcons.APPLICATION),
		Library("library", ADMIcons.LIBRARY),
		Plugin("plugin", ADMIcons.PLUGIN);

		companion object {
			fun parse(s: String?): Kind? = entries.firstOrNull { it.label.equals(s, ignoreCase = true) }
		}
	}

	/** The directory the manifest lives in, else the unit's own directory. */
	val projectDir: String get() = manifest?.let { java.io.File(it).parent } ?: dir

	/** A stable identity across refreshes: kind, name and file. */
	val id: String get() = "${kind.label}:$name@$file"
}

/**
 * The units of the open workspace and the one the tool window is looking at.
 * Every tab keys off [selected]; a change is broadcast to [Listener]s.
 *
 * Discovery runs `adm list --json`, which walks the project for declaration
 * blocks and finds each one's manifest, so the panel and the CLI agree on
 * what a project is.
 */
@Service(Service.Level.PROJECT)
class ADMWorkspace(private val project: Project) : Disposable {
	interface Listener : EventListener {
		/** The unit list was refreshed. */
		fun unitsChanged(units: List<ADMUnit>) {}

		/** A different unit was selected (or none). */
		fun selectionChanged(unit: ADMUnit?) {}
	}

	private val dispatcher = EventDispatcher.create(Listener::class.java)

	@Volatile var units: List<ADMUnit> = emptyList()
		private set

	@Volatile var selected: ADMUnit? = null
		private set

	/** Why the last refresh produced no units, for the empty state. */
	@Volatile var lastError: String? = null
		private set

	fun addListener(listener: Listener, parent: Disposable) = dispatcher.addListener(listener, parent)

	fun select(unit: ADMUnit?) {
		if (unit?.id == selected?.id) return
		selected = unit
		// Listeners read documents and editors (the Info tab opens the
		// manifest); a mouse handler or a popup callback on the EDT carries no
		// implicit read access on the current platform, so grant it here.
		WriteIntentReadAction.run { dispatcher.multicaster.selectionChanged(unit) }
	}

	/** Re-lists the units; keeps the selection when its unit still exists. */
	fun refresh(onDone: (() -> Unit)? = null) {
		val root = project.basePath ?: return
		ADMCli.async(project, listOf("list", "--json", root), root) { result ->
			val parsed = if (result.ok) parseUnits(result.stdout, root) else null
			lastError = if (result.ok) null else result.failure()
			units = parsed.orEmpty().sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
			dispatcher.multicaster.unitsChanged(units)
			val keep = selected?.let { s -> units.firstOrNull { it.id == s.id } }
			select(keep ?: units.firstOrNull { it.kind == ADMUnit.Kind.Application } ?: units.firstOrNull())
			onDone?.invoke()
		}
	}

	private fun parseUnits(text: String, root: String): List<ADMUnit>? {
		val json = runCatching { com.google.gson.JsonParser.parseString(text) }.getOrNull() ?: return null
		val array = when {
			json.isJsonArray -> json.asJsonArray
			json.isJsonObject -> json.asJsonObject.arr("units") ?: return null
			else -> return null
		}
		return array.mapNotNull { el ->
			val o = el as? JsonObject ?: return@mapNotNull null
			val kind = ADMUnit.Kind.parse(o.str("kind")) ?: return@mapNotNull null
			val name = o.str("name") ?: return@mapNotNull null
			val file = absolute(o.str("file") ?: return@mapNotNull null, root)
			ADMUnit(
				kind = kind,
				name = name,
				file = file,
				line = o.get("line")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1,
				dir = o.str("dir")?.let { absolute(it, root) } ?: java.io.File(file).parent,
				manifest = o.str("manifest")?.takeIf { it.isNotBlank() }?.let { absolute(it, root) },
				version = o.str("version")?.takeIf { it.isNotBlank() },
			)
		}
	}

	private fun absolute(path: String, root: String): String {
		val f = java.io.File(path)
		return if (f.isAbsolute) f.path else java.io.File(root, path).path
	}


	override fun dispose() {}

	companion object {
		fun getInstance(project: Project): ADMWorkspace = project.service()
	}
}
