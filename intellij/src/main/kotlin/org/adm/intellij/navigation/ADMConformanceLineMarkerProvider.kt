package org.adm.intellij.navigation

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.ui.awt.RelativePoint
import org.adm.intellij.lang.ADMFileType
import org.adm.intellij.lsp.ADMImplementsParams
import org.adm.intellij.lsp.ADMLanguageServer
import org.adm.intellij.lsp.ADMReferences
import org.adm.intellij.lsp.ADMServerRefresh
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.awt.event.MouseEvent

/**
 * Conformance gutter markers: "Implements X" on types, "Implemented by" on
 * interfaces and datatypes. Conformance is the compiler's call -- ADM is
 * structural, a text scan of the body cannot know -- so the data comes from
 * the server's `adm/implements` (one request per file, cached on the
 * document stamp); a click navigates, through a chooser when there is more
 * than one target.
 */
class ADMConformanceLineMarkerProvider : LineMarkerProviderDescriptor() {
	private val log = Logger.getInstance(ADMConformanceLineMarkerProvider::class.java)

	override fun getName(): String = "ADM conformance markers"

	/** Nothing cheap to offer: the data needs the server, see [collectSlowLineMarkers]. */
	override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

	override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
		val byFile = elements.groupBy { it.containingFile }
		for ((file, fileElements) in byFile) {
			if (file == null || file.fileType != ADMFileType.INSTANCE) continue
			val markers = markersFor(file)
			if (markers.isEmpty()) continue
			for (element in fileElements) {
				if (element is PsiWhiteSpace || element.firstChild != null) continue
				val marker = markers[element.textRange.startOffset] ?: continue
				val tooltip = when (marker.kind) {
					MarkerKind.Implements -> "Implements " + marker.targets.joinToString(", ") { it.name }
					MarkerKind.ImplementedBy -> "Implemented by " + marker.targets.joinToString(", ") { it.name }
				}
				val icon = when (marker.kind) {
					MarkerKind.Implements -> AllIcons.Gutter.ImplementingMethod
					MarkerKind.ImplementedBy -> AllIcons.Gutter.ImplementedMethod
				}
				result.add(
					LineMarkerInfo(
						element,
						TextRange(element.textRange.startOffset, element.textRange.startOffset + 1),
						icon,
						{ tooltip },
						{ event, _ -> marker.navigate(file.project, event) },
						GutterIconRenderer.Alignment.LEFT,
						{ tooltip },
					)
				)
			}
		}
	}

	private data class Target(val name: String, val uri: String, val line: Int, val column: Int)

	private enum class MarkerKind { Implements, ImplementedBy }

	private data class Marker(val offset: Int, val kind: MarkerKind, val targets: List<Target>) {
		fun navigate(project: Project, event: MouseEvent) {
			if (targets.size == 1) {
				open(project, targets[0])
				return
			}
			JBPopupFactory.getInstance()
				.createPopupChooserBuilder(targets)
				.setTitle(if (kind == MarkerKind.Implements) "Implemented Interfaces" else "Implementations")
				.setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { it.name })
				.setItemChosenCallback { open(project, it) }
				.createPopup()
				.show(RelativePoint(event))
		}

		private fun open(project: Project, target: Target) {
			val file = VirtualFileManager.getInstance().findFileByUrl(target.uri) ?: return
			OpenFileDescriptor(project, file, target.line, target.column).navigate(true)
		}
	}

	/** Keyed on the server instance too: a restarted server is a new answer. */
	private data class MarkerCache(val stamp: Long, val server: Any, val markers: Map<Int, Marker>)

	private fun markersFor(file: PsiFile): Map<Int, Marker> {
		val stamp = file.viewProvider.modificationStamp
		val server = ADMReferences.serverFor(file.project)
		val cached = file.getUserData(MARKERS_KEY)
		if (cached != null && cached.stamp == stamp && cached.server === server) return cached.markers
		// A failed request (no server yet, a timeout while the workspace is
		// still being analyzed after a restart) is not an answer: caching it
		// under this stamp hid the markers until the file was edited again.
		// Ask the daemon to run this pass again shortly instead.
		val markers = computeMarkers(file)
		if (server == null || markers == null) {
			ADMServerRefresh.retryLater(file.project)
			return emptyMap()
		}
		file.putUserData(MARKERS_KEY, MarkerCache(stamp, server, markers))
		return markers
	}

	/** Markers for the file, or null when the server could not be asked. */
	private fun computeMarkers(file: PsiFile): Map<Int, Marker>? {
		val project = file.project
		val virtualFile = file.virtualFile ?: return emptyMap()
		val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return emptyMap()
		val server = ADMReferences.serverFor(project) ?: return null
		val params = ADMImplementsParams(TextDocumentIdentifier(ADMReferences.lspUri(project, virtualFile)))
		val entries = try {
			// Explicit timeout: see ADMReferences.REQUEST_TIMEOUT_MS.
			server.sendRequestSync(10_000) { ls -> (ls as ADMLanguageServer).admImplements(params) }
		} catch (t: Throwable) {
			log.info("adm/implements unavailable: ${t.message}")
			return null
		} ?: return emptyMap()

		val out = HashMap<Int, Marker>()
		for (entry in entries) {
			val kind = when (entry.kind) {
				"implements" -> MarkerKind.Implements
				"implementedBy" -> MarkerKind.ImplementedBy
				else -> continue
			}
			val start = entry.range?.start ?: continue
			if (start.line >= document.lineCount) continue
			val offset = document.getLineStartOffset(start.line) + start.character
			val targets = entry.targets.orEmpty().mapNotNull { t ->
				val name = t.name ?: return@mapNotNull null
				val location = t.location ?: return@mapNotNull null
				val pos = location.range?.start ?: return@mapNotNull null
				Target(name, location.uri, pos.line, pos.character)
			}
			if (targets.isNotEmpty()) out[offset] = Marker(offset, kind, targets)
		}
		return out
	}

	companion object {
		private val MARKERS_KEY = Key.create<MarkerCache>("adm.conformance.markers")
	}
}
