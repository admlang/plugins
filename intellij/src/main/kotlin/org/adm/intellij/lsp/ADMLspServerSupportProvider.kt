package org.adm.intellij.lsp

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider
import org.adm.intellij.lang.ADMFileType
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class ADMLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerSupportProvider.LspServerStarter) {
        if (file.fileType != ADMFileType.INSTANCE) {
            LOG.debug("Ignoring non-ADM file for LSP: ${file.path} (${file.fileType.name})")
            return
        }
        LOG.info("Requesting ADM LSP for ${file.path}")
        ADMLspLogs.logToFile(project, "Requesting ADM LSP for ${file.path}")
        try {
            // Reuse a single descriptor per project so we don't accidentally
            // start multiple servers (which can duplicate inlays/diagnostics).
            val descriptor = DESCRIPTORS.computeIfAbsent(project) { ADMLspServerDescriptor(it) }
            // Some IDE builds require associating the server with the file that
            // triggered startup; call the 2-arg overload when available.
            val started = ensureServerStartedForFile(serverStarter, descriptor, file)
            if (!started) {
                serverStarter.ensureServerStarted(descriptor)
            }
        } catch (t: Throwable) {
            LOG.warn("Failed to start ADM LSP", t)
            ADMLspLogs.logToFile(project, "Failed to start ADM LSP: ${t.message}")
            // Do not rethrow: throwing here can prevent later attempts to start
            // or restart the server during the session.
            return
        }
    }

    private fun ensureServerStartedForFile(
        serverStarter: LspServerSupportProvider.LspServerStarter,
        descriptor: ADMLspServerDescriptor,
        file: VirtualFile,
    ): Boolean {
        return try {
            val method: Method? = serverStarter.javaClass.methods.firstOrNull {
                it.name == "ensureServerStarted" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0].isAssignableFrom(descriptor.javaClass) &&
                    VirtualFile::class.java.isAssignableFrom(it.parameterTypes[1])
            }
            if (method != null) {
                method.invoke(serverStarter, descriptor, file)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private val LOG = logger<ADMLspServerSupportProvider>()
        private val DESCRIPTORS = ConcurrentHashMap<Project, ADMLspServerDescriptor>()
    }
}
