package org.adm.intellij.actions

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.adm.intellij.lang.ADMFileType
import org.adm.intellij.run.ADMExec
import java.nio.charset.StandardCharsets

class ADMFormatFileAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && file.fileType == ADMFileType.INSTANCE && file.isValid && !file.isDirectory
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (file.fileType != ADMFileType.INSTANCE || file.isDirectory) return

        FileDocumentManager.getInstance().saveAllDocuments()

        val adm = ADMExec.resolve(project.basePath)
        if (adm == null) {
            notify(project, NotificationType.ERROR, "Could not locate the `adm` executable (configure it under Settings → ADM).")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Formatting ${file.name}", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val cmd = GeneralCommandLine(adm.toString(), "fmt", file.path)
                    .withWorkDirectory(project.basePath)
                    .withCharset(StandardCharsets.UTF_8)
                cmd.withEnvironment(ADMExec.env())

                val output: ProcessOutput = try {
                    CapturingProcessHandler(cmd).runProcess(120_000)
                } catch (t: Throwable) {
                    val msg = (t as? ExecutionException)?.message ?: t.message ?: t.javaClass.simpleName
                    notify(project, NotificationType.ERROR, "Failed to run `adm fmt`: $msg")
                    return
                }

                ApplicationManager.getApplication().invokeLater {
                    file.refresh(false, false)
                }

                if (output.exitCode == 0) {
                    notify(project, NotificationType.INFORMATION, "Formatted ${file.name}.")
                } else {
                    val details = (output.stderr + "\n" + output.stdout).trim()
                    notify(project, NotificationType.ERROR, "Format failed for ${file.name}.${if (details.isNotBlank()) "\n$details" else ""}")
                }
            }
        })
    }

    private fun notify(project: Project, type: NotificationType, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ADM")
            .createNotification(content, type)
            .notify(project)
    }
}

