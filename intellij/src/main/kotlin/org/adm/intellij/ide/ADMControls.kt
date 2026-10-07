package org.adm.intellij.ide

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * A list of named sections on the left and the selected one's page on the
 * right, the layout of the Info and Toolchain tabs. The list is as wide as
 * its longest entry needs. [proportionKey] is kept for callers; the column is
 * not resizable.
 */
class ADMSectionsView(@Suppress("UNUSED_PARAMETER") proportionKey: String, @Suppress("UNUSED_PARAMETER") defaultProportion: Float = 0.18f) : JPanel(BorderLayout()) {
	/** One entry of the list. */
	class Section(val id: String, val icon: Icon)

	private val model = DefaultListModel<Section>()
	private val list = JBList(model)
	private val cards = CardLayout()
	private val body = JPanel(cards)

	/** The selected section's id, or null. */
	val selectedId: String? get() = list.selectedValue?.id

	private val listScroll = JBScrollPane(list).apply {
		border = JBUI.Borders.customLineRight(JBUI.CurrentTheme.ToolWindow.borderColor())
	}

	init {
		list.selectionMode = ListSelectionModel.SINGLE_SELECTION
		list.cellRenderer = object : ColoredListCellRenderer<Section>() {
			override fun customizeCellRenderer(l: JList<out Section>, value: Section?, index: Int, selected: Boolean, hasFocus: Boolean) {
				value ?: return
				ipad = JBUI.insets(4, 8)
				icon = value.icon
				append(value.id)
			}
		}
		list.addListSelectionListener { if (!it.valueIsAdjusting) list.selectedValue?.let { s -> cards.show(body, s.id) } }
		add(listScroll, BorderLayout.WEST)
		add(body, BorderLayout.CENTER)
	}

	/** Width for the longest entry plus its icon and padding, so "Dependencies" fits without slack. */
	private fun fit() {
		val width = (list.preferredSize.width + JBUI.scale(12)).coerceAtLeast(JBUI.scale(96))
		listScroll.preferredSize = Dimension(width, 0)
		listScroll.revalidate()
	}

	/** Adds a section; its page scrolls when taller than the tab. */
	fun add(id: String, icon: Icon, page: JComponent, scroll: Boolean = true) {
		val host = if (scroll) JBScrollPane(page).apply { border = JBUI.Borders.empty(); viewport.background = UIUtil.getPanelBackground() } else page
		body.add(host, id)
		model.addElement(Section(id, icon))
		fit()
	}

	fun clear() {
		body.removeAll()
		model.clear()
	}

	/** Selects the section named [id], else the first one. */
	fun select(id: String?) {
		if (model.size() == 0) return
		val index = (0 until model.size()).firstOrNull { model[it].id == id } ?: 0
		list.selectedIndex = index
		cards.show(body, model[index].id)
		body.revalidate()
		body.repaint()
	}
}

/**
 * A read-only field whose value is a set picked from [options] through a
 * checklist popup, shown comma-separated. "Any" clears the set and shows the
 * placeholder, so the field never holds a spelling the tools do not know.
 */
class ADMMultiPickField(private val options: List<String>, initial: String) : JPanel(BorderLayout()) {
	private val field = JBTextField().apply {
		isEditable = false
		emptyText.text = "any"
	}
	private val picked = LinkedHashSet<String>()

	/** The selection as `a, b`, empty for any. */
	val text: String get() = picked.joinToString(", ")

	init {
		set(initial)
		val arrow = JButton(AllIcons.General.ArrowDown).apply {
			isFocusable = false
			toolTipText = "Choose"
			preferredSize = Dimension(JBUI.scale(28), field.preferredSize.height)
			addActionListener { open() }
		}
		add(field, BorderLayout.CENTER)
		add(arrow, BorderLayout.EAST)
		field.addMouseListener(object : java.awt.event.MouseAdapter() {
			override fun mouseClicked(e: java.awt.event.MouseEvent) = open()
		})
	}

	/** Takes a comma-separated value, keeping only known options. */
	fun set(value: String) {
		picked.clear()
		for (v in value.split(',')) {
			val t = v.trim()
			if (options.any { it.equals(t, ignoreCase = true) }) picked.add(options.first { it.equals(t, ignoreCase = true) })
		}
		field.text = text
	}

	private fun open() {
		val checks = CheckBoxList<String>()
		checks.addItem(ANY, ANY, picked.isEmpty())
		for (o in options) checks.addItem(o, o, o in picked)
		checks.setCheckBoxListListener { index, checked ->
			val item = checks.getItemAt(index) ?: return@setCheckBoxListListener
			if (item == ANY) {
				if (checked) {
					picked.clear()
					for (i in 1 until checks.itemsCount) checks.setItemSelected(checks.getItemAt(i), false)
				} else if (picked.isEmpty()) {
					checks.setItemSelected(ANY, true)
				}
			} else {
				if (checked) picked.add(item) else picked.remove(item)
				checks.setItemSelected(ANY, picked.isEmpty())
			}
			field.text = text
		}
		// A fixed height (eight rows, then a scrollbar) keeps the popup on
		// screen; the popup then flips above the field when below is short.
		checks.visibleRowCount = minOf(checks.itemsCount, 8)
		val scroll = JBScrollPane(checks).apply {
			border = JBUI.Borders.empty()
			preferredSize = Dimension(width.coerceAtLeast(JBUI.scale(160)), checks.preferredScrollableViewportSize.height + JBUI.scale(4))
		}
		val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, checks)
			.setRequestFocus(true)
			.setFocusable(true)
			.setCancelOnClickOutside(true)
			.createPopup()
		popup.addListener(object : JBPopupListener {
			override fun onClosed(event: LightweightWindowEvent) {
				field.text = text
			}
		})
		popup.showUnderneathOf(this)
	}

	private companion object {
		const val ANY = "Any"
	}
}

/**
 * A version field with a Bump menu at its end: Major, Minor or Patch raise the
 * text in place; saving writes it. Two-part versions ("0.1") read as x.y.0.
 */
class ADMVersionField(initial: String) : JPanel(BorderLayout()) {
	private val input = JBTextField(initial)

	/** The version as typed or bumped. */
	val value: String get() = input.text.trim()

	init {
		val bump = JButton("Bump", AllIcons.General.ArrowDown).apply {
			isFocusable = false
			horizontalTextPosition = javax.swing.SwingConstants.LEFT
			toolTipText = "Raise the version"
			addActionListener {
				val group = com.intellij.openapi.actionSystem.DefaultActionGroup()
				for ((part, label) in listOf(2 to "Patch", 1 to "Minor", 0 to "Major")) {
					group.add(object : com.intellij.openapi.project.DumbAwareAction(label) {
						override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
							input.text = bumped(input.text, part)
						}
					})
				}
				JBPopupFactory.getInstance()
					.createActionGroupPopup(null, group, com.intellij.openapi.actionSystem.impl.SimpleDataContext.EMPTY_CONTEXT, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
					.showUnderneathOf(this)
			}
		}
		add(input, BorderLayout.CENTER)
		add(bump, BorderLayout.EAST)
	}

	private companion object {
		/** [index] 0 = major, 1 = minor, 2 = patch; lower parts reset to zero. */
		fun bumped(version: String, index: Int): String {
			val parts = version.trim().ifEmpty { "0.0.0" }.split('.').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }.toMutableList()
			while (parts.size < 3) parts.add(0)
			parts[index] = parts[index] + 1
			for (i in index + 1 until 3) parts[i] = 0
			return parts.take(3).joinToString(".")
		}
	}
}

/**
 * Picks permissions for `[requires] permissions`: a checkbox tree of every
 * permission the program's services declare, grouped by service
 * (`adm.storage` holds `read` and `write`). A fully ticked group is written
 * as `adm.storage.*`, everything ticked as `*`, otherwise the names. The
 * catalog is loaded through [load] the first time the field opens.
 */
class ADMPermissionPickField(initial: String, private val load: ((List<Pair<String, String>>, String?) -> Unit) -> Unit) : JPanel(BorderLayout()) {
	private val field = JBTextField().apply {
		isEditable = false
		emptyText.text = "everything the code reaches"
	}
	private var picked: List<String> = parse(initial)
	private var catalog: List<Pair<String, String>>? = null

	/** The selection as `a, b`, empty for no narrowing. */
	val text: String get() = picked.joinToString(", ")

	init {
		field.text = text
		val arrow = JButton(AllIcons.General.ArrowDown).apply {
			isFocusable = false
			toolTipText = "Choose"
			preferredSize = Dimension(JBUI.scale(28), field.preferredSize.height)
			addActionListener { open() }
		}
		add(field, BorderLayout.CENTER)
		add(arrow, BorderLayout.EAST)
		field.addMouseListener(object : java.awt.event.MouseAdapter() {
			override fun mouseClicked(e: java.awt.event.MouseEvent) = open()
		})
	}

	private fun parse(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

	private fun open() {
		val known = catalog
		if (known != null) return show(known)
		field.text = "Loading permissions…"
		load { entries, error ->
			field.text = text
			if (error != null) {
				com.intellij.openapi.ui.Messages.showErrorDialog(this, error, "Permissions")
				return@load
			}
			catalog = entries
			show(entries)
		}
	}

	/** `adm.storage.read` belongs to the group `adm.storage`; a name with fewer than two dots stands alone. */
	private fun groupOf(permission: String): String = permission.substringBeforeLast('.', "")

	/** Whether the current selection covers a permission: by name, by its group's glob, or by `*`. */
	private fun covers(permission: String): Boolean = picked.any { p ->
		p == "*" || p == permission || (p.endsWith(".*") && permission.startsWith(p.dropLast(1))) || permission.startsWith("$p.")
	}

	private fun show(entries: List<Pair<String, String>>) {
		val root = com.intellij.ui.CheckedTreeNode("Everything (*)")
		val groups = entries.groupBy { groupOf(it.first) }.toSortedMap()
		val leaves = ArrayList<Pair<com.intellij.ui.CheckedTreeNode, String>>()
		for ((group, members) in groups) {
			val parent = if (group.isEmpty() || members.size == 1) root else com.intellij.ui.CheckedTreeNode("$group.*").also { root.add(it) }
			for ((name, description) in members.sortedBy { it.first }) {
				val leaf = com.intellij.ui.CheckedTreeNode(name)
				leaf.isChecked = covers(name)
				parent.add(leaf)
				leaves += leaf to description
			}
		}
		val descriptions = leaves.associate { it.first.userObject as String to it.second }
		val tree = com.intellij.ui.CheckboxTree(object : com.intellij.ui.CheckboxTree.CheckboxTreeCellRenderer() {
			override fun customizeRenderer(tree: javax.swing.JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
				val name = (value as? com.intellij.ui.CheckedTreeNode)?.userObject?.toString() ?: return
				textRenderer.append(name)
				descriptions[name]?.takeIf { it.isNotEmpty() }?.let { textRenderer.append("  $it", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES) }
			}
		}, root)
		tree.isRootVisible = true
		com.intellij.util.ui.tree.TreeUtil.expandAll(tree)
		val scroll = JBScrollPane(tree).apply {
			border = JBUI.Borders.empty()
			preferredSize = Dimension(width.coerceAtLeast(JBUI.scale(420)), JBUI.scale(minOf(24 * (leaves.size + groups.size + 1), 360)))
		}
		val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, tree)
			.setRequestFocus(true)
			.setFocusable(true)
			.setCancelOnClickOutside(true)
			.createPopup()
		popup.addListener(object : JBPopupListener {
			override fun onClosed(event: LightweightWindowEvent) {
				picked = collect(root)
				field.text = text
			}
		})
		popup.showUnderneathOf(this)
	}

	/** The narrowing list a checked tree means: `*` for all, a group's glob when all of it is ticked, else names. */
	private fun collect(root: com.intellij.ui.CheckedTreeNode): List<String> {
		fun leavesOf(node: com.intellij.ui.CheckedTreeNode): List<com.intellij.ui.CheckedTreeNode> =
			if (node.childCount == 0) listOf(node) else (0 until node.childCount).flatMap { leavesOf(node.getChildAt(it) as com.intellij.ui.CheckedTreeNode) }
		val all = leavesOf(root)
		if (all.isNotEmpty() && all.all { it.isChecked }) return listOf("*")
		val out = ArrayList<String>()
		for (i in 0 until root.childCount) {
			val child = root.getChildAt(i) as com.intellij.ui.CheckedTreeNode
			val name = child.userObject.toString()
			if (child.childCount == 0) {
				if (child.isChecked) out += name
				continue
			}
			val members = leavesOf(child)
			if (members.all { it.isChecked }) out += name else members.filter { it.isChecked }.forEach { out += it.userObject.toString() }
		}
		return out
	}
}
