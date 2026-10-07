package org.adm.intellij.settings

/**
 * A compiler backend selectable in settings. The ids match the values the
 * `adm --backend` flag accepts, and [DEFAULT] mirrors the compiler's own
 * default so the plugin never silently selects a different one. LLVM is the
 * only one since the C code generator was removed; a stored "c" reads back
 * as [DEFAULT].
 */
enum class ADMBackend(val id: String, val label: String) {
	LLVM("llvm", "LLVM (default)"),
	;

	override fun toString(): String = label

	companion object {
		val DEFAULT = LLVM

		fun fromId(id: String?): ADMBackend {
			val wanted = id?.trim()?.lowercase().orEmpty()
			return entries.firstOrNull { it.id == wanted } ?: DEFAULT
		}
	}
}
