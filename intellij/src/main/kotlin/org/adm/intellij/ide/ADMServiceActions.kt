package org.adm.intellij.ide

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.table.DefaultTableModel

/**
 * Runs an action of a running service's panel: asks for its inputs, asks the
 * confirmation the service declared, sends `call` over the process's IDE
 * channel and shows what came back.
 */
internal object ADMServiceActions {
	private val gson = Gson()

	fun run(project: Project, service: String, action: WireAction, client: ADMServicesPanel.Client) {
		val label = action.label ?: action.action ?: return
		val params = action.params.orEmpty()
		val args = if (params.isEmpty()) JsonObject() else (ArgumentsDialog(project, label, params).ask() ?: return)
		val confirm = action.confirm.orEmpty()
		if (confirm.isNotEmpty() && Messages.showYesNoDialog(project, confirm, label, null) != Messages.YES) return
		client.enqueue("call $service ${action.action} ${gson.toJson(args)}") { line ->
			val reply = line?.let { gson.fromJson(it, JsonObject::class.java) }
			when {
				reply == null -> notify(project, "$label: the program is gone", NotificationType.WARNING)
				reply.get("kind")?.asString == "error" -> notify(project, "$label: ${reply.get("message")?.asString}", NotificationType.ERROR)
				reply.get("ok")?.asBoolean == true -> {
					val value = reply.get("value")
					val shown = if (value == null || value.isJsonNull) "done" else if (value.isJsonPrimitive) value.asString else value.toString()
					notify(project, "$label: $shown", NotificationType.INFORMATION)
				}
				else -> notify(project, "$label failed: ${reply.getAsJsonObject("error")?.get("message")?.asString}", NotificationType.ERROR)
			}
		}
	}

	fun notify(project: Project, text: String, type: NotificationType) {
		NotificationGroupManager.getInstance().getNotificationGroup("ADM").createNotification(text, type).notify(project)
	}

	/** The inputs of an action, one per parameter; a parameter left empty takes its default. */
	private class ArgumentsDialog(project: Project, private val label: String, private val params: List<WireParam>) : DialogWrapper(project, true) {
		private val inputs = params.map { inputFor(it) }

		init {
			title = label
			init()
		}

		override fun createCenterPanel(): JComponent = panel {
			params.forEachIndexed { i, p ->
				val row = row("${p.name}:") { cell(inputs[i]).align(Align.FILL) }
				val default = p.default
				if (default != null) row.rowComment("${p.type}, $default when left empty")
				else row.rowComment(p.type.orEmpty())
			}
		}

		private fun inputFor(p: WireParam): JComponent = when (p.type) {
			"bool" -> JBCheckBox().apply { isSelected = p.default == "true" }
			"enum" -> ComboBox(p.choices.orEmpty().toTypedArray()).apply {
				p.default?.substringAfterLast('.')?.let { selectedItem = it }
			}
			else -> JBTextField().apply {
				// A string's default is written with its quotes.
				text = p.default?.removeSurrounding("\"").orEmpty()
			}
		}

		/** The arguments as `call` takes them, or null when cancelled. */
		fun ask(): JsonObject? {
			if (!showAndGet()) return null
			val out = JsonObject()
			params.forEachIndexed { i, p ->
				val name = p.name ?: return@forEachIndexed
				when (val input = inputs[i]) {
					is JBCheckBox -> out.addProperty(name, input.isSelected)
					is ComboBox<*> -> input.selectedItem?.toString()?.let { out.addProperty(name, it) }
					is JBTextField -> {
						val text = input.text.trim()
						if (text.isEmpty() && p.default != null) return@forEachIndexed
						when (p.type) {
							"int" -> text.toLongOrNull()?.let { out.addProperty(name, it) } ?: out.addProperty(name, text)
							"float" -> text.toDoubleOrNull()?.let { out.addProperty(name, it) } ?: out.addProperty(name, text)
							else -> out.addProperty(name, text)
						}
					}
				}
			}
			return out
		}
	}
}

/**
 * The lab of one running service: its conditions, which the program's calls
 * meet for the rest of the run, set from here as a test sets them. A value
 * is edited in its cell (JSON: a number, true/false, a duration such as
 * 150ms, a string, null); Reset puts one back, Reset All every one.
 */
internal class ADMLabDialog(
	private val project: Project,
	private val service: String,
	private val client: ADMServicesPanel.Client,
) : DialogWrapper(project, false) {
	private val gson = Gson()
	private val model = object : DefaultTableModel(arrayOf("Condition", "Type", "Value", "Default"), 0) {
		override fun isCellEditable(row: Int, column: Int) = column == 2
	}
	private val table = JBTable(model)
	private val offline = JBCheckBox("Offline: every call that can fail does")
	private var types = emptyList<String>()
	private var filling = false

	init {
		title = "Lab: ${service.substringAfterLast('.')}"
		setOKButtonText("Close")
		init()
		model.addTableModelListener { e ->
			if (filling || e.column != 2 || e.firstRow < 0) return@addTableModelListener
			val row = e.firstRow
			val name = model.getValueAt(row, 0)?.toString() ?: return@addTableModelListener
			val text = model.getValueAt(row, 2)?.toString()?.trim().orEmpty()
			send("set $name ${asJson(types.getOrNull(row).orEmpty(), text)}")
		}
		offline.addActionListener { send(if (offline.isSelected) "offline" else "online") }
		send("")
	}

	override fun createCenterPanel(): JComponent = panel {
		row { cell(JBScrollPane(table).apply { preferredSize = Dimension(560, 220) }).align(Align.FILL) }
		row { cell(offline) }
		row {
			button("Reset Selected") {
				val row = table.selectedRow
				if (row >= 0) send("reset ${model.getValueAt(row, 0)}")
			}
			button("Reset All") { send("reset") }
		}
	}

	/** A value typed into the table as the JSON `set` takes: a string or a duration in quotes, the rest as typed. */
	private fun asJson(type: String, text: String): String = when {
		type == "string" || type == "duration" -> gson.toJson(text.removeSurrounding("\""))
		else -> text
	}

	private fun send(operation: String) {
		val line = if (operation.isEmpty()) "lab $service" else "lab $service $operation"
		client.enqueue(line) { reply -> show(reply) }
	}

	private fun show(line: String?) {
		val reply = line?.let { gson.fromJson(it, JsonObject::class.java) } ?: return
		if (reply.get("kind")?.asString == "error") {
			ADMServiceActions.notify(project, "Lab: ${reply.get("message")?.asString}", NotificationType.ERROR)
			return
		}
		filling = true
		try {
			model.rowCount = 0
			val names = mutableListOf<String>()
			for (element in reply.getAsJsonArray("conditions") ?: com.google.gson.JsonArray()) {
				val c = element.asJsonObject
				val type = c.get("type")?.asString.orEmpty()
				names.add(type)
				model.addRow(arrayOf(c.get("name")?.asString, type, text(c.get("value")), text(c.get("default"))))
			}
			types = names
			offline.isSelected = reply.get("offline")?.asBoolean == true
		} finally {
			filling = false
		}
	}

	private fun text(value: com.google.gson.JsonElement?): String = when {
		value == null || value.isJsonNull -> "null"
		value.isJsonPrimitive -> value.asString
		else -> value.toString()
	}
}
