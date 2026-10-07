package org.adm.intellij.actions

import com.intellij.ide.IdeView
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

enum class ADMDeclKind(val display: String, val keyword: String) {
    Application("ADM Application", "application"),
    Module("ADM Module", "module"),
    Library("ADM Library", "library"),
    Plugin("ADM Plugin", "plugin"),
}

open class ADMNewDeclarationAction(private val kind: ADMDeclKind) : AnAction(kind.display) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = targetDirectory(e) ?: run {
            notify(project, NotificationType.ERROR, "Select a folder (or an ADM file) in the Project view first.")
            return
        }

        val dialog = ADMNewDeclarationDialog(project, kind)
        if (!dialog.showAndGet()) return

        val fileName = normalizeFileName(dialog.fileName)
        if (fileName.isBlank()) return

        val declName = dialog.declName.trim()
        val content = render(kind, declName, dialog.isPartial)

        WriteCommandAction.runWriteCommandAction(project, "Create ${kind.display}", null, Runnable {
            val file = dir.createFile(fileName)
            val doc = FileDocumentManager.getInstance().getDocument(file.virtualFile) ?: return@Runnable
            doc.setText(content)
            FileDocumentManager.getInstance().saveDocument(doc)
            openAndMoveCaret(project, file.virtualFile, "/*caret*/")
        })
    }

    private fun targetDirectory(e: AnActionEvent): PsiDirectory? {
        val project = e.project ?: return null
        val ideView: IdeView? = e.getData(LangDataKeys.IDE_VIEW)
        ideView?.directories?.firstOrNull()?.let { return it }
        ideView?.getOrChooseDirectory()?.let { return it }

        val psi = e.getData(CommonDataKeys.PSI_ELEMENT)
        if (psi is PsiDirectory) return psi
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (file != null) {
            val target: VirtualFile? = if (file.isDirectory) file else file.parent
            if (target != null) return PsiManager.getInstance(project).findDirectory(target)
        }
        return null
    }

    private fun normalizeFileName(name: String): String {
        val raw = name.trim().ifBlank { return "" }
        val base = if (raw.endsWith(".adm")) raw else "$raw.adm"
        return base.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun render(kind: ADMDeclKind, name: String, partial: Boolean): String {
        val header = buildString {
            if (partial) append("partial ")
            append(kind.keyword)
            val n = name.trim()
            if (n.isNotEmpty()) {
                append(' ')
                append(n)
            }
            append(" {\n")
        }
        return when (kind) {
            ADMDeclKind.Application -> header +
                "\tdef new(args string[]) int {\n" +
                "\t\t/*caret*/\n" +
                "\t\treturn 0\n" +
                "\t}\n" +
                "}\n"
            else -> header + "\t/*caret*/\n}\n"
        }
    }

    private fun openAndMoveCaret(project: Project, file: VirtualFile, marker: String) {
        val editors = FileEditorManager.getInstance(project).openFile(file, true)
        val editor = editors.firstOrNull { it is com.intellij.openapi.fileEditor.TextEditor } as? com.intellij.openapi.fileEditor.TextEditor
        val textEditor = editor?.editor ?: return
        val doc = textEditor.document
        val idx = doc.text.indexOf(marker)
        if (idx < 0) return
        WriteCommandAction.runWriteCommandAction(project, "Position caret", null, Runnable {
            doc.deleteString(idx, idx + marker.length)
        })
        SwingUtilities.invokeLater {
            val caret = textEditor.caretModel
            caret.moveToOffset(idx)
            textEditor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
        }
    }

    private fun notify(project: Project, type: NotificationType, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ADM")
            .createNotification(content, type)
            .notify(project)
    }
}

class ADMNewApplicationAction : ADMNewDeclarationAction(ADMDeclKind.Application)
class ADMNewModuleAction : ADMNewDeclarationAction(ADMDeclKind.Module)
class ADMNewLibraryAction : ADMNewDeclarationAction(ADMDeclKind.Library)
class ADMNewPluginAction : ADMNewDeclarationAction(ADMDeclKind.Plugin)

private class ADMNewDeclarationDialog(
    project: Project,
    private val kind: ADMDeclKind,
) : DialogWrapper(project, true) {
    private val fileNameField = JBTextField("", 30)
    private val declNameField = JBTextField("", 30)
    private val partialBox = JBCheckBox("partial", false)

    val fileName: String get() = fileNameField.text
    val declName: String get() = declNameField.text
    val isPartial: Boolean get() = partialBox.isSelected

    init {
        title = "New ${kind.display}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        val form = JPanel()
        form.layout = java.awt.GridBagLayout()
        val gc = java.awt.GridBagConstraints().apply {
            fill = java.awt.GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx = 0
            gridy = 0
            insets = java.awt.Insets(4, 0, 4, 0)
        }

        fun addRow(label: String, field: JComponent) {
            gc.gridx = 0
            gc.weightx = 0.0
            form.add(JLabel(label), gc)
            gc.gridx = 1
            gc.weightx = 1.0
            form.add(field, gc)
            gc.gridy++
        }

        addRow("File name:", fileNameField)
        addRow("${kind.keyword} name:", declNameField)
        gc.gridx = 1
        gc.weightx = 1.0
        form.add(partialBox, gc)

        panel.add(form, BorderLayout.CENTER)
        panel.preferredSize = Dimension(520, 140)

        SwingUtilities.invokeLater {
            fileNameField.requestFocusInWindow()
        }
        return panel
    }
}
