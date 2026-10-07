package org.adm.intellij.lsp

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Re-runs the highlighting daemon so server-backed presentation (the
 * conformance gutter markers, the "N usages" code vision) is recomputed.
 *
 * Both are computed by daemon passes and cached by the platform until the
 * document changes. A language server (re)start does not change any
 * document, so without a nudge the editor kept showing whatever the previous
 * server had answered -- or nothing, when the request ran before the new
 * server had finished analysing the workspace.
 */
object ADMServerRefresh {
	private const val RETRY_DELAY_SECONDS = 4L
	private val retryPending = AtomicBoolean(false)

	/** Restart the daemon for every open editor of [project] now. */
	fun restartDaemon(project: Project) {
		ApplicationManager.getApplication().invokeLater {
			if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart()
		}
	}

	/**
	 * A request went unanswered (no server yet, or a timeout while the server
	 * is still analysing): restart the daemon a few seconds from now so the
	 * pass runs again. Collapses concurrent callers into one retry.
	 */
	fun retryLater(project: Project) {
		if (!retryPending.compareAndSet(false, true)) return
		AppExecutorUtil.getAppScheduledExecutorService().schedule({
			retryPending.set(false)
			restartDaemon(project)
		}, RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
	}
}
