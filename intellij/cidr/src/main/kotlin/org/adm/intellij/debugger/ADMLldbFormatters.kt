package org.adm.intellij.debugger

import java.io.File

/**
 * Locates `adm_formatters.py`, the LLDB formatter script installed next to the
 * `adm` binary by build.sh (`~/.adm/lldb/adm_formatters.py`).
 *
 * ADM arrays are runtime handles (`{data, len, cap, buf, flags}`) and DWARF cannot
 * express "data points at len elements" when the count lives in a sibling member,
 * so array CONTENTS only appear once this script registers its synthetic children.
 * Without it a variable still shows its ADM type and `len`, just not the elements.
 *
 * `ADM_LLDB_FORMATTERS` overrides the path; `ADM_HOME` relocates the install root.
 */
object ADMLldbFormatters {
	fun script(): File? {
		val candidates = ArrayList<File>()
		System.getenv("ADM_LLDB_FORMATTERS")?.trim()?.takeIf { it.isNotEmpty() }?.let {
			candidates.add(File(it))
		}
		System.getenv("ADM_HOME")?.trim()?.takeIf { it.isNotEmpty() }?.let {
			candidates.add(File(it).resolve("lldb/adm_formatters.py"))
		}
		System.getProperty("user.home")?.let {
			candidates.add(File(it).resolve(".adm/lldb/adm_formatters.py"))
		}
		return candidates.firstOrNull { it.isFile }
	}

	/** The LLDB command that registers the formatters, or null when not installed. */
	fun importCommand(): String? {
		val file = script() ?: return null
		return "command script import \"${file.absolutePath}\""
	}
}
