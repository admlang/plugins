package org.adm.intellij.ide

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel

// Wire payloads of the std.ide line protocol (Gson-bound).
private class WireMember {
	var member: String? = null
	var kind: String? = null
	var label: String? = null
	var refreshMs: Long = 1000
	var unit: String? = null
}

private class WireService {
	var name: String? = null
	var status: String? = null
	var members: List<WireMember>? = null
}

private class WireList {
	var kind: String? = null
	var services: List<WireService>? = null
}

/**
 * The Services panel of the ADM tool window: service list on the left, the
 * selected service's published members (`@ide` in ADM code) on the right,
 * refreshed by polling the process's std.ide endpoint.
 *
 * Several ADM processes may run at once (a monorepo's microservices); each
 * gets a session, and the panel shows the one belonging to the application
 * the project dropdown selected.
 */
class ADMServicesPanel(private val project: Project, parentDisposable: Disposable) : JBPanelWithEmptyText(BorderLayout()), Disposable {
	private val gson = Gson()

	private val listModel = DefaultListModel<WireService>()
	private val serviceList = JBList(listModel)
	private val detailPanel = JPanel()
	private val detailHost = JBPanelWithEmptyText(BorderLayout())
	private val detailScroll = JBScrollPane(detailPanel).apply { border = JBUI.Borders.empty() }
	private val statusLabel = JBLabel("", SwingConstants.LEFT)
	private val splitter = OnePixelSplitter(false, 0.25f)

	/** Called on the EDT whenever a process attaches or detaches. */
	var onSessionsChanged: (() -> Unit)? = null

	// name -> services from the last `list` reply
	private var services: List<WireService> = emptyList()
	private var widgets: Map<String, MemberWidget> = emptyMap()
	private var widgetsFor: String? = null

	/** One attached process. */
	private inner class Session(val handler: ProcessHandler, val port: Int, val appName: String?) {
		val client = Client(port)
	}

	private val sessions = LinkedHashMap<ProcessHandler, Session>()
	private var shown: Session? = null

	/** The client whose replies drive the panel: the shown session's. */
	@Volatile private var client: Client? = null

	init {
		Disposer.register(parentDisposable, this)
		serviceList.selectionMode = ListSelectionModel.SINGLE_SELECTION
		serviceList.border = JBUI.Borders.empty(6, 4)
		serviceList.cellRenderer = object : ColoredListCellRenderer<WireService>() {
			override fun customizeCellRenderer(
				list: javax.swing.JList<out WireService>,
				value: WireService?,
				index: Int,
				selected: Boolean,
				hasFocus: Boolean,
			) {
				value ?: return
				ipad = JBUI.insets(3, 4)
				icon = when (value.status) {
					"Started", "Starting", "Restarting" -> AllIcons.Actions.Execute
					"Stopped", "Stopping", "Failed" -> AllIcons.Actions.Suspend
					else -> AllIcons.RunConfigurations.TestUnknown
				}
				append(value.name ?: "?")
				append("  ${value.status ?: ""}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
				if (value.members.orEmpty().isNotEmpty()) {
					append("  ●", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.namedColor("Label.infoForeground", JBColor.GRAY)))
				}
			}
		}
		serviceList.addListSelectionListener {
			if (!it.valueIsAdjusting) rebuildDetail()
		}
		detailPanel.layout = BoxLayout(detailPanel, BoxLayout.Y_AXIS)
		detailPanel.border = JBUI.Borders.empty(12, 16)
		detailHost.emptyText.text = "Select a service"

		splitter.firstComponent = JBScrollPane(serviceList).apply { border = JBUI.Borders.empty() }
		splitter.secondComponent = detailHost
		statusLabel.border = JBUI.Borders.empty(4, 8)
		statusLabel.foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
		emptyText.text = "No running ADM process"
		emptyText.appendSecondaryText("Run or debug the selected application to inspect its services", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)

		ADMWorkspace.getInstance(project).addListener(object : ADMWorkspace.Listener {
			override fun selectionChanged(unit: ADMUnit?) = showFor(unit)
		}, this)
	}

	/** Whether a process of the application [appName] is attached. */
	fun isRunning(appName: String): Boolean = sessions.values.any { it.appName == appName }

	fun attach(handler: ProcessHandler, port: Int, appName: String?) {
		val session = Session(handler, port, appName)
		sessions[handler] = session
		session.client.start()
		showFor(ADMWorkspace.getInstance(project).selected)
		onSessionsChanged?.invoke()
	}

	fun detach(handler: ProcessHandler?) {
		if (handler == null) {
			sessions.values.forEach { it.client.stop() }
			sessions.clear()
		} else {
			// A stale terminate (the previous run ending after the next one
			// attached) must not tear down a live session.
			sessions.remove(handler)?.client?.stop() ?: return
		}
		ApplicationManager.getApplication().invokeLater {
			showFor(ADMWorkspace.getInstance(project).selected)
			onSessionsChanged?.invoke()
		}
	}

	/** Switches the panel to the session of [unit]'s application, or to no session. */
	private fun showFor(unit: ADMUnit?) {
		val next = when {
			unit == null -> sessions.values.firstOrNull()
			else -> sessions.values.firstOrNull { it.appName == unit.name }
		}
		if (next === shown && (next == null) == (componentCount == 0)) return
		shown = next
		client = next?.client
		services = emptyList()
		widgets = emptyMap()
		widgetsFor = null
		listModel.clear()
		detailPanel.removeAll()
		removeAll()
		if (next != null) {
			add(splitter, BorderLayout.CENTER)
			add(statusLabel, BorderLayout.SOUTH)
			setStatus("Connecting to 127.0.0.1:${next.port}…")
		}
		revalidate()
		repaint()
		rebuildDetail()
	}

	override fun dispose() {
		detach(null)
	}

	private fun setStatus(text: String) {
		statusLabel.text = text
	}

	private fun selectedService(): WireService? = serviceList.selectedValue

	private fun onList(reply: WireList) {
		services = reply.services.orEmpty()
		val previous = serviceList.selectedValue?.name
		listModel.clear()
		for (svc in services) {
			listModel.addElement(svc)
		}
		if (previous != null) {
			val idx = services.indexOfFirst { it.name == previous }
			if (idx >= 0) serviceList.selectedIndex = idx
		} else {
			// First fill: select the first service with published members.
			val idx = services.indexOfFirst { it.members.orEmpty().isNotEmpty() }
			if (idx >= 0) serviceList.selectedIndex = idx
		}
		serviceList.repaint()
		rebuildDetail()
	}

	private fun rebuildDetail() {
		val svc = selectedService()
		if (svc?.name == widgetsFor && widgets.isNotEmpty()) return
		detailPanel.removeAll()
		widgets = emptyMap()
		widgetsFor = svc?.name
		val members = svc?.members.orEmpty()
		if (svc == null || members.isEmpty()) {
			detailHost.remove(detailScroll)
			detailHost.emptyText.text = if (svc == null) "Select a service" else "This service publishes no IDE members"
		} else {
			if (detailScroll.parent !== detailHost) detailHost.add(detailScroll, BorderLayout.CENTER)
			val built = LinkedHashMap<String, MemberWidget>()
			for (m in members) {
				val w = MemberWidget(m)
				built[m.member ?: continue] = w
				detailPanel.add(w.component)
				detailPanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(10)))
			}
			widgets = built
		}
		detailPanel.revalidate()
		detailHost.revalidate()
		detailHost.repaint()
	}

	private fun onSnapshot(reply: JsonObject) {
		val service = reply.get("service")?.asString ?: return
		if (service != widgetsFor) return
		val values = reply.obj("values") ?: return
		for ((member, widget) in widgets) {
			val value = values.get(member) ?: continue
			widget.update(value)
		}
	}

	/** One published member rendered by its declared kind. */
	private class MemberWidget(private val meta: WireMember) {
		private val title = JBLabel(meta.label ?: meta.member ?: "?")
		private val valueLabel = JBLabel("—")
		private val tableModel = DefaultTableModel()
		private val table = JBTable(tableModel)
		private val series = SeriesGraph()
		val component: JPanel = JPanel(BorderLayout())

		init {
			title.font = title.font.deriveFont(title.font.style or java.awt.Font.BOLD)
			title.border = JBUI.Borders.emptyBottom(6)
			component.add(title, BorderLayout.NORTH)
			when (meta.kind) {
				"table" -> {
					table.setShowGrid(false)
					val scroll = JBScrollPane(table)
					scroll.preferredSize = Dimension(400, JBUI.scale(140))
					component.add(scroll, BorderLayout.CENTER)
				}
				"series" -> {
					series.preferredSize = Dimension(400, JBUI.scale(80))
					component.add(series, BorderLayout.CENTER)
					component.add(valueLabel, BorderLayout.SOUTH)
				}
				else -> component.add(valueLabel, BorderLayout.CENTER)
			}
			component.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(if (meta.kind == "table") 190 else 130))
			component.alignmentX = 0f
		}

		fun update(value: com.google.gson.JsonElement) {
			when (meta.kind) {
				"table" -> {
					val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return
					val columns = obj.arr("columns")?.map { it.asString }?.toTypedArray() ?: return
					val rows = obj.arr("rows")?.map { row ->
						row.asJsonArray.map { it.asString }.toTypedArray()
					}?.toTypedArray() ?: emptyArray()
					tableModel.setDataVector(rows, columns)
				}
				"series" -> {
					val v = value.takeIf { it.isJsonPrimitive }?.asDouble ?: return
					series.push(v)
					valueLabel.text = formatValue(v) + suffix()
				}
				else -> {
					valueLabel.text = (if (value.isJsonPrimitive) value.asString else value.toString()) + suffix()
				}
			}
		}

		private fun suffix(): String = meta.unit?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""

		private fun formatValue(v: Double): String =
			if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else "%.2f".format(v)
	}

	/** Minimal line graph over a ring buffer of samples. */
	private class SeriesGraph : JPanel() {
		private val samples = ArrayDeque<Double>()
		private val capacity = 240

		fun push(v: Double) {
			if (samples.size >= capacity) samples.removeFirst()
			samples.addLast(v)
			repaint()
		}

		override fun paintComponent(g: Graphics) {
			super.paintComponent(g)
			if (samples.size < 2) return
			val g2 = g as Graphics2D
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			val min = samples.min()
			val max = samples.max()
			val span = (max - min).takeIf { it > 0.0 } ?: 1.0
			val w = width.toDouble()
			val h = height.toDouble()
			val step = w / (capacity - 1)
			g2.color = JBColor.BLUE
			var px = -1.0
			var py = -1.0
			samples.forEachIndexed { i, v ->
				val x = i * step
				val y = h - 4 - (v - min) / span * (h - 8)
				if (px >= 0) g2.drawLine(px.toInt(), py.toInt(), x.toInt(), y.toInt())
				px = x
				py = y
			}
		}
	}

	/**
	 * Socket client for one running process. One request in flight at a time;
	 * polls `list` and, for the selected service, `snapshot` once a second.
	 */
	private inner class Client(private val port: Int) {
		@Volatile private var running = true
		private var thread: Thread? = null

		fun start() {
			thread = Thread({ run() }, "ADM IDE inspector :$port").apply {
				isDaemon = true
				start()
			}
		}

		fun stop() {
			running = false
			thread?.interrupt()
		}

		// `adm run` builds before it starts the program, and a large
		// application builds for longer than any fixed wait: the port is
		// tried until it answers, for as long as the process lives (stop()
		// ends it). A program that `adm run --watch` or `--hot` restarts
		// closes the connection and binds the port again, so a lost
		// connection goes back to waiting instead of ending the session.
		private fun run() {
			try {
				while (running) {
					val socket = connect() ?: return
					try {
						serve(socket)
					} catch (e: InterruptedException) {
						throw e
					} catch (_: Exception) {
						// The program went away; wait for it to come back.
					} finally {
						try {
							socket.close()
						} catch (_: Exception) {
						}
					}
					status("Waiting for the program on 127.0.0.1:$port…")
					Thread.sleep(500)
				}
			} catch (_: InterruptedException) {
			}
		}

		private fun status(text: String) {
			ApplicationManager.getApplication().invokeLater {
				if (client === this) setStatus(text)
			}
		}

		private fun connect(): Socket? {
			var told = false
			while (running) {
				try {
					val s = Socket()
					s.connect(InetSocketAddress("127.0.0.1", port), 1000)
					return s
				} catch (_: java.io.IOException) {
					if (!told) {
						told = true
						status("Waiting for the program on 127.0.0.1:$port…")
					}
					Thread.sleep(500)
				}
			}
			return null
		}

		private fun serve(s: Socket) {
			val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
			val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
			status("Connected to 127.0.0.1:$port")
			while (running) {
				val listLine = request(writer, reader, "list") ?: return
				val list = gson.fromJson(listLine, WireList::class.java)
				if (list?.kind == "list") {
					ApplicationManager.getApplication().invokeLater {
						if (client === this) onList(list)
					}
				}
				val selected = widgetsFor
				if (selected != null) {
					val snapLine = request(writer, reader, "snapshot $selected") ?: return
					val snap = gson.fromJson(snapLine, JsonObject::class.java)
					if (snap?.get("kind")?.asString == "snapshot") {
						ApplicationManager.getApplication().invokeLater {
							if (client === this) onSnapshot(snap)
						}
					}
				}
				Thread.sleep(1000)
			}
		}

		private fun request(writer: OutputStreamWriter, reader: BufferedReader, line: String): String? {
			writer.write(line)
			writer.write("\n")
			writer.flush()
			return reader.readLine()
		}
	}
}
