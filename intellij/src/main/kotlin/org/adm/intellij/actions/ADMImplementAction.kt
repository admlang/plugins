package org.adm.intellij.actions

import com.google.gson.JsonObject
import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.ide.util.gotoByName.ChooseByNamePopupComponent
import com.intellij.ide.util.gotoByName.SimpleChooseByNameModel
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import org.adm.intellij.ADMIcons
import org.adm.intellij.ide.ADMCli
import org.adm.intellij.ide.arr
import org.adm.intellij.ide.asArrayOrNull
import org.adm.intellij.ide.asObjectOrNull
import org.adm.intellij.ide.int
import org.adm.intellij.ide.objects
import org.adm.intellij.ide.str
import java.io.File
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * Generate › Implement Interface/Datatype…: a searchable chooser over every
 * interface and datatype the workspace can see (its own, libraries, std),
 * multi-select, then the members the chosen ones require and the type does
 * not yet declare are appended as stubs. Conformance is structural, so no
 * clause is written; the members are the implementation.
 *
 * A required member is stubbed from its own declaration line in the
 * interface's source, so parameter names come through as written.
 */
class ADMImplementAction : ADMGenerateAction("Implement Interface/Datatype…", "Add the methods or fields an interface or datatype requires") {
	/** One chooser entry. */
	class Candidate(val module: String, val name: String, val kind: String, val file: String, val line: Int, val members: List<Member>, val embeds: List<Pair<String, String>>) {
		val qualified: String get() = "$module.$name"
	}

	class Member(val name: String, val kind: String, val file: String, val line: Int)

	override fun generate(project: Project, editor: Editor, file: PsiFile, type: ADMTypeAtCaret) {
		val root = project.basePath ?: return
		ProgressManager.getInstance().run(object : Task.Modal(project, "Reading Interfaces and Datatypes", true) {
			var candidates: List<Candidate> = emptyList()
			var error: String? = null

			override fun run(indicator: ProgressIndicator) {
				indicator.isIndeterminate = true
				val r = ADMCli.run(project, listOf("doc", "--workspace", "--json", "--full-doc", "--root", root), root)
				if (!r.ok) {
					error = r.failure()
					return
				}
				candidates = parse(r.json()?.asObjectOrNull()?.arr("workspace").objects())
			}

			override fun onSuccess() {
				val err = error
				if (err != null) {
					notify(project, "Could not list interfaces: $err")
					return
				}
				if (candidates.isEmpty()) {
					notify(project, "No interfaces or datatypes found")
					return
				}
				choose(project, editor, file, type, candidates)
			}
		})
	}

	private fun parse(modules: List<JsonObject>): List<Candidate> {
		val out = ArrayList<Candidate>()
		for (m in modules) {
			val module = m.str("name") ?: continue
			for (x in m.arr("exports").objects()) {
				val kind = x.str("kindName") ?: continue
				if (kind != "interface" && kind != "datatype") continue
				val name = x.str("name") ?: continue
				val members = x.arr("members").objects().mapNotNull { mem ->
					Member(mem.str("name") ?: return@mapNotNull null, mem.str("kindName") ?: "", mem.str("file") ?: "", mem.int("line") ?: 0)
				}
				val embeds = x.arr("implements").objects().mapNotNull { e -> (e.str("module") ?: return@mapNotNull null) to (e.str("name") ?: return@mapNotNull null) }
				out.add(Candidate(module, name, kind, x.str("file") ?: "", x.int("line") ?: 0, members, embeds))
			}
		}
		return out.sortedWith(compareBy({ it.name }, { it.module }))
	}

	private fun choose(project: Project, editor: Editor, file: PsiFile, type: ADMTypeAtCaret, candidates: List<Candidate>) {
		val byName = candidates.groupBy { it.name }
		val model = object : SimpleChooseByNameModel(project, "Implement interface or datatype (Ctrl+click or Shift+arrows to pick several)", null) {
			override fun getNames(): Array<String> = byName.keys.toTypedArray()
			override fun getElementsByName(name: String, pattern: String): Array<Any> = byName[name].orEmpty().toTypedArray()
			override fun getElementName(element: Any): String = (element as Candidate).name
			override fun getFullName(element: Any): String = (element as Candidate).qualified
			override fun getListCellRenderer(): ListCellRenderer<*> = object : ColoredListCellRenderer<Any>() {
				override fun customizeCellRenderer(list: JList<out Any>, value: Any?, index: Int, selected: Boolean, hasFocus: Boolean) {
					val c = value as? Candidate ?: return
					icon = if (c.kind == "interface") ADMIcons.INTERFACE else ADMIcons.DATATYPE
					append(c.name)
					append("  ${c.module}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
				}
			}
			override fun getNotInMessage(): String = "No matches"
			override fun getNotFoundMessage(): String = "No interface or datatype found"
		}
		val popup = ChooseByNamePopup.createPopup(project, model, file)
		popup.setShowListForEmptyPattern(true)
		popup.setSearchInAnyPlace(true)
		val chosen = ArrayList<Candidate>()
		popup.invoke(object : ChooseByNamePopupComponent.Callback() {
			override fun elementChosen(element: Any) {
				(element as? Candidate)?.let(chosen::add)
			}

			override fun onClose() {
				if (chosen.isNotEmpty()) apply(project, editor, file, type, chosen, candidates)
			}
		}, ModalityState.current(), true)
	}

	private fun apply(project: Project, editor: Editor, file: PsiFile, type: ADMTypeAtCaret, chosen: List<Candidate>, all: List<Candidate>) {
		val index = all.associateBy { it.qualified }
		val present = type.memberNames
		val stubs = ArrayList<String>()
		val seen = HashSet<String>()
		val visited = HashSet<String>()
		fun collect(c: Candidate) {
			if (!visited.add(c.qualified)) return
			for ((module, name) in c.embeds) index["$module.$name"]?.let(::collect)
			for (m in c.members) {
				if (m.name in present || !seen.add(m.name)) continue
				val decl = declarationLine(project, m) ?: continue
				stubs.add(
					if (c.kind == "interface" || m.kind == "function") "def $decl {\n\t\n}" else decl,
				)
			}
		}
		chosen.forEach(::collect)
		if (stubs.isEmpty()) {
			notify(project, "${type.node.name} already has every member of ${chosen.joinToString { it.name }}", NotificationType.INFORMATION)
			return
		}
		// Fields go first, then methods, each block blank-line separated.
		val ordered = stubs.filter { !it.startsWith("def ") } + stubs.filter { it.startsWith("def ") }
		insert(project, editor, file, type, ordered, caretLineOffset = if (ordered.first().startsWith("def ")) 1 else 0)
	}

	/** The member's declaration as written, `name(params) ret` or `name type`, from its source line. */
	private fun declarationLine(project: Project, m: Member): String? {
		if (m.file.isEmpty() || m.line <= 0) return null
		val f = File(m.file).let { if (it.isAbsolute) it else File(project.basePath ?: ".", m.file) }
		val line = runCatching { f.useLines { it.drop(m.line - 1).firstOrNull() } }.getOrNull() ?: return null
		val text = line.substringBefore("//").trim().removePrefix("def ").trimEnd('{', ' ', '\t')
		return text.ifEmpty { null }
	}

	private fun notify(project: Project, text: String, type: NotificationType = NotificationType.WARNING) {
		NotificationGroupManager.getInstance().getNotificationGroup("ADM").createNotification(text, type).notify(project)
	}
}
