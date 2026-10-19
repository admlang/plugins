package org.adm.intellij.run

/**
 * Where an ADM run configuration runs its program: on this machine, or in
 * the browser (`--os wasm`: `adm run` serves the page and opens the browser,
 * `adm test` runs the suites in a headless one; both print the program's
 * output, which the run console shows). `[apps.X.compat] os` in adm.toml
 * only documents where an application works; this is what picks the target.
 */
enum class ADMPlatform(val id: String, val label: String) {
	NATIVE("native", "Native"),
	WEB("web", "Web (browser)"),
	;

	override fun toString(): String = label

	/**
	 * The `adm` arguments for this platform: on the web, `build`, `run` and
	 * `test` get `--os wasm` and [env] as `--env KEY=value` entries, since a
	 * page has no process environment to inherit. An `--os` already in
	 * [args] wins, as it does for `--backend`.
	 */
	fun apply(args: List<String>, env: Map<String, String>): List<String> {
		if (this != WEB) return args
		val subcommand = args.firstOrNull()?.trim()?.lowercase()
		if (subcommand !in WEB_SUBCOMMANDS) return args
		val out = ArrayList(args)
		if (args.none { it == "--os" || it.startsWith("--os=") }) out.addAll(listOf("--os", "wasm"))
		if (subcommand != "build") {
			for ((k, v) in env) out.addAll(listOf("--env", "$k=$v"))
		}
		return out
	}

	companion object {
		val DEFAULT = NATIVE

		private val WEB_SUBCOMMANDS = setOf("build", "run", "test")

		fun fromId(id: String?): ADMPlatform {
			val wanted = id?.trim()?.lowercase().orEmpty()
			return entries.firstOrNull { it.id == wanted } ?: DEFAULT
		}
	}
}
