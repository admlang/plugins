package org.adm.intellij.run

/**
 * How an ADM run configuration follows source changes while its program
 * runs: not at all, `adm run --hot` (changed code is swapped into the
 * running program; a change beyond function bodies restarts it), or
 * `adm run --watch` (every change rebuilds and restarts). Only `run` takes
 * the flags, and only on this machine: a web build runs once in the
 * browser, so a Web configuration runs with reload off whatever is saved.
 */
enum class ADMReload(val id: String, val label: String, val flag: String?) {
	OFF("off", "Off", null),
	HOT("hot", "Hot (swap changed code)", "--hot"),
	WATCH("watch", "Watch (rebuild and restart)", "--watch"),
	;

	override fun toString(): String = label

	/**
	 * The `adm` arguments for this mode: `run` gets [flag]. A `--hot` or
	 * `--watch` already in [args] wins, as an `--os` does for the platform,
	 * so a configuration whose arguments spell the mode out keeps it.
	 */
	fun apply(args: List<String>): List<String> {
		val flag = this.flag ?: return args
		if (args.firstOrNull()?.trim()?.lowercase() != "run") return args
		if (args.any { it == "--hot" || it == "--watch" }) return args
		return args + flag
	}

	companion object {
		val DEFAULT = OFF

		fun fromId(id: String?): ADMReload {
			val wanted = id?.trim()?.lowercase().orEmpty()
			return entries.firstOrNull { it.id == wanted } ?: DEFAULT
		}
	}
}
