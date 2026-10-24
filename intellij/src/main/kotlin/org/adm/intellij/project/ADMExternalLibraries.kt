package org.adm.intellij.project

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Alarm
import java.nio.file.Path
import java.util.Objects
import javax.swing.Icon
import org.adm.intellij.ADMIcons
import org.adm.intellij.ide.ADMCli
import org.adm.intellij.ide.ADMUnit
import org.adm.intellij.ide.ADMWorkspace
import org.adm.intellij.ide.asArrayOrNull
import org.adm.intellij.ide.asObjectOrNull
import org.adm.intellij.ide.objects
import org.adm.intellij.ide.str
import org.adm.intellij.lang.ADMFileType

/**
 * One node under External Libraries: the ADM SDK (prelude and std folders)
 * or a package `adm.lock` pins (its source folder).
 */
class ADMLibrary(
	id: String,
	private val title: String,
	private val location: String?,
	private val icon: Icon,
	private val roots: List<VirtualFile>,
) : SyntheticLibrary(id, null), ItemPresentation {
	override fun getSourceRoots(): Collection<VirtualFile> = roots

	override fun getPresentableText(): String = title

	override fun getLocationString(): String? = location

	override fun getIcon(unused: Boolean): Icon = icon

	override fun equals(other: Any?): Boolean =
		other is ADMLibrary && other.comparisonId == comparisonId && other.title == title &&
			other.location == location && other.roots == roots

	override fun hashCode(): Int = Objects.hash(comparisonId, roots)
}

/**
 * The libraries the project's ADM code builds against, shown under External
 * Libraries the way a Go project shows its SDK. The folders come from the
 * compiler, so the tree and the build agree: `adm env --json` names the
 * prelude and std a build started in the project loads, and `adm list
 * packages --json` the source folder of each installed package that the
 * adm.lock of each unit pins. A folder inside the project's own content (the
 * ADM repository, where std is project source) is left out, so no file
 * belongs to the project and to a library at once.
 */
@Service(Service.Level.PROJECT)
class ADMExternalLibraries(private val project: Project) : Disposable {
	@Volatile var libraries: List<ADMLibrary> = emptyList()
		private set

	private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

	init {
		ADMWorkspace.getInstance(project).addListener(object : ADMWorkspace.Listener {
			override fun unitsChanged(units: List<ADMUnit>) = refresh()
		}, this)
		// adm get, adm lib remove and adm install rewrite adm.lock or adm.toml;
		// a package installed from the lock as it stands changes neither, so
		// the Libraries tab calls refresh itself after an install.
		project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
			override fun after(events: List<VFileEvent>) {
				if (events.any { it.path.substringAfterLast('/') in WATCHED }) refresh()
			}
		})
	}

	/** Reads the roots again after a short pause, so a burst of changes runs the commands once. */
	fun refresh() {
		alarm.cancelAllRequests()
		alarm.addRequest(::load, 300)
	}

	private class Candidate(val id: String, val title: String, val location: String?, val icon: Icon, val paths: List<String>)

	private fun load() {
		if (project.isDisposed) return
		val base = project.basePath ?: return
		val candidates = listOfNotNull(sdk(base)) + packages(base)
		val inProject = contentRoots()
		val fs = LocalFileSystem.getInstance()
		val next = candidates.mapNotNull { c ->
			val roots = c.paths
				.filter { p -> real(Path.of(p)).let { r -> inProject.none { r.startsWith(it) } } }
				.mapNotNull { fs.refreshAndFindFileByNioFile(Path.of(it)) }
			if (roots.isEmpty()) null else ADMLibrary(c.id, c.title, c.location, c.icon, roots)
		}
		if (next == libraries) return
		val oldRoots = libraries.flatMap { it.sourceRoots }
		libraries = next
		ApplicationManager.getApplication().invokeLater({
			WriteAction.run<RuntimeException> {
				AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, null, oldRoots, next.flatMap { it.sourceRoots }, "ADM")
			}
		}, project.disposed)
	}

	/** The prelude and std a build started in the project loads; null when adm cannot say. */
	private fun sdk(base: String): Candidate? {
		val r = ADMCli.run(project, listOf("env", "--json"), base, 30_000)
		val o = r.takeIf { it.ok }?.json().asObjectOrNull() ?: return null
		val paths = listOfNotNull(o.str("prelude"), o.str("std")).filter { it.isNotBlank() }
		if (paths.isEmpty()) return null
		val version = o.str("version")?.takeIf { it.isNotBlank() }
		val location = o.str("lib")?.takeIf { it.isNotBlank() } ?: Path.of(paths.last()).parent?.toString()
		return Candidate(SDK_ID, listOfNotNull("ADM SDK", version).joinToString(" "), location, ADMIcons.FILE, paths)
	}

	/**
	 * The installed packages pinned by the adm.lock of each unit's project
	 * folder (a monorepo holds several), one node per package version.
	 */
	private fun packages(base: String): List<Candidate> {
		val dirs = ADMWorkspace.getInstance(project).units.map { it.projectDir }.distinct().ifEmpty { listOf(base) }
		val byRoot = LinkedHashMap<String, Candidate>()
		for (dir in dirs) {
			val r = ADMCli.run(project, listOf("list", "packages", "--json", "--no-pull", "--dir", dir), dir, 60_000)
			for (p in r.takeIf { it.ok }?.json().asArrayOrNull().objects()) {
				val root = p.str("root")?.takeIf { it.isNotBlank() } ?: continue
				val library = p.str("library") ?: continue
				val version = p.str("version").orEmpty()
				byRoot.putIfAbsent(root, Candidate("adm-package:$library@$version", "$library $version".trim(), null, ADMIcons.LIBRARY, listOf(root)))
			}
		}
		return byRoot.values.sortedBy { it.title }
	}

	private fun contentRoots(): List<Path> = ReadAction.compute<List<Path>, RuntimeException> {
		if (project.isDisposed) emptyList()
		else ProjectRootManager.getInstance(project).contentRoots.mapNotNull { f ->
			runCatching { real(f.toNioPath()) }.getOrNull()
		}
	}

	override fun dispose() {}

	companion object {
		/** The SDK node keeps one id across versions, so the tree keeps it expanded after an update. */
		private const val SDK_ID = "adm-sdk"

		/** The files whose change can change the package list. */
		private val WATCHED = setOf("adm.lock", "adm.toml")

		/** The path with its symbolic links resolved: ~/.adm/lib/std may point into the project. */
		private fun real(p: Path): Path = runCatching { p.toRealPath() }.getOrDefault(p.toAbsolutePath().normalize())

		fun getInstance(project: Project): ADMExternalLibraries = project.service()

		/** The service when something already started it; a project with no ADM code never does. */
		fun ifCreated(project: Project): ADMExternalLibraries? = project.getServiceIfCreated(ADMExternalLibraries::class.java)
	}
}

/** Hands [ADMExternalLibraries] to the project model and the project view. */
class ADMLibraryRootsProvider : AdditionalLibraryRootsProvider() {
	override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> =
		ADMExternalLibraries.ifCreated(project)?.libraries.orEmpty()

	override fun getRootsToWatch(project: Project): Collection<VirtualFile> =
		getAdditionalProjectLibraries(project).flatMap { it.sourceRoots }
}

/**
 * Lists the libraries when a project with ADM files opens. A project without
 * any runs no adm command; one that gains ADM code later lists them when the
 * ADM tool window opens.
 */
class ADMExternalLibrariesStartup : ProjectActivity {
	override suspend fun execute(project: Project) {
		val hasADM = smartReadAction(project) {
			FileTypeIndex.containsFileOfType(ADMFileType.INSTANCE, GlobalSearchScope.projectScope(project))
		}
		if (!hasADM) return
		// Created first so it hears the units the refresh below lists.
		ADMExternalLibraries.getInstance(project)
		ADMWorkspace.getInstance(project).refresh()
	}
}
