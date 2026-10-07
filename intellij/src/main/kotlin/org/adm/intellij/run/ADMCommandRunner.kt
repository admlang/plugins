package org.adm.intellij.run

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.fileEditor.FileDocumentManager
import java.nio.charset.StandardCharsets

object ADMCommandRunner {
    private val log = Logger.getInstance(ADMCommandRunner::class.java)

    fun run(project: Project, title: String, workDir: VirtualFile?, args: List<String>) {
        FileDocumentManager.getInstance().saveAllDocuments()

        val workPath = workDir?.path ?: project.basePath
        if (workPath == null) {
            Messages.showErrorDialog(project, "Cannot determine working directory for ADM command.", "ADM")
            return
        }

        val adm = ADMExec.resolve(project.basePath)
        if (adm == null) {
            Messages.showErrorDialog(
                project,
                "Could not locate the `adm` executable.\n\nPATH=${System.getenv("PATH") ?: ""}\n\n" +
                    "Tip: launch IntelliJ from a shell so it inherits your PATH, or place `adm` in ~/.adm/bin/adm or <project>/bin/adm.",
                "ADM"
            )
            return
        }

        val commandLine = GeneralCommandLine(adm.toString())
            .withWorkDirectory(workPath)
            .withCharset(StandardCharsets.UTF_8)
            .withParameters(args)
        commandLine.withEnvironment(ADMExec.env())

        val handler = try {
            KillableProcessHandler(commandLine)
        } catch (t: Throwable) {
            log.warn("Failed to start adm process", t)
            Messages.showErrorDialog(project, "Failed to start `adm`: ${t.message ?: t.javaClass.simpleName}", "ADM")
            return
        }

        ProcessTerminatedListener.attach(handler)
        val console: ConsoleView = ConsoleViewImpl(project, true)
        console.attachToProcess(handler)

        val descriptor = RunContentDescriptor(console, handler, console.component, title)
        descriptor.setAutoFocusContent(true)

        ApplicationManager.getApplication().invokeLater {
            val executor = DefaultRunExecutor.getRunExecutorInstance()
            RunContentManager.getInstance(project).showRunContent(executor, descriptor)
            handler.startNotify()
        }
    }
}
