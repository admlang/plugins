package org.adm.intellij.structure

/**
 * Ad-hoc verification entry point for the declaration scanner. [ADMDeclarations]
 * is pure text -> tree, so it can be exercised without an IDE instance; the
 * plugin's Gradle `test` task cannot start a plain JUnit executor under the
 * IntelliJ test instrumentation, so this runs via JavaExec instead.
 */
object ADMDeclarationsSelfCheck {
	private var failures = 0

	@JvmStatic
	fun main(args: Array<String>) {
		// With file arguments, dump each file's tree instead of self-checking:
		// a smoke test against real sources.
		if (args.isNotEmpty()) {
			for (path in args) {
				val text = java.io.File(path).readText()
				val roots = ADMDeclarations.compute(text)
				val sb = StringBuilder()
				for (node in roots) render(node, 0, sb)
				val count = sb.lineSequence().count { it.isNotBlank() }
				println("== $path ($count nodes) ==")
				println(sb.toString().lineSequence().take(12).joinToString("\n"))
			}
			return
		}

		check(
			"application and its functions",
			"""
			application demo {
				def new(args string[]) int {
					return 0
				}

				def helper() int {
					return 1
				}
			}
			""".trimIndent(),
			"""
			Application demo
			  Function new
			  Function helper
			""".trimIndent(),
		)

		check(
			"dotted module with nested type",
			"""
			partial module std.gfx.color {
				use std.testing

				struct RGBA {
					red byte
				}

				def fromKelvin(k float64) Color {
					return none
				}
			}
			""".trimIndent(),
			"""
			Module std.gfx.color
			  Type RGBA
			  Function fromKelvin
			""".trimIndent(),
		)

		check(
			"check suite with a test",
			"""
			partial module builtin {
				check "Arrays" {
					@test()
					def TestInfixOperators() {
						let x = [1,2,3]
					}
				}
			}
			""".trimIndent(),
			"""
			Module builtin
			  Suite Arrays
			    Function TestInfixOperators
			""".trimIndent(),
		)

		check(
			"nested suites keep nesting",
			"""
			partial module m {
				check "Outer" {
					check "Inner" {
						@test()
						def deep() {}
					}
					@test()
					def shallow() {}
				}
			}
			""".trimIndent(),
			"""
			Module m
			  Suite Outer
			    Suite Inner
			      Function deep
			    Function shallow
			""".trimIndent(),
		)

		check(
			"match/case are not declarations",
			"""
			module m {
				def f() int {
					match T {
						case byte:
							return 1
					}
					return 0
				}
			}
			""".trimIndent(),
			"""
			Module m
			  Function f
			""".trimIndent(),
		)

		check(
			"bodiless units, aliases and signatures are siblings",
			"""
			partial module std.units {
				@dimension(unit = "px")
				type Pixels

				@dimension(unit = "%")
				type Percent

				type Extent = Pixels | Percent

				interface Shape {
					def area() float
					def name() string
				}

				@dimension(unit = "em")
				type FontRelative

				def f() {
				}

				def native(a int) int;

				def after() {
				}
			}
			""".trimIndent(),
			"""
			Module std.units
			  Type Pixels
			  Type Percent
			  Type Extent
			  Type Shape
			    Function area
			    Function name
			  Type FontRelative
			  Function f
			  Function native
			  Function after
			""".trimIndent(),
		)

		// Signature text.
		val fn = ADMDeclarations.compute("module m {\n\tdef add(a int, b int) int {\n\t\treturn a\n\t}\n}")
			.single().children.single()
		expect("signature", "add :: (a int, b int) int", "${fn.name} :: ${fn.detail}")

		// Ranges and degenerate input.
		val text = "module m {\n\tdef f() {\n\t}\n}"
		val root = ADMDeclarations.compute(text).single()
		expect("range covers body", "true", (root.endOffset == text.length).toString())
		expect("empty input", "0", ADMDeclarations.compute("").size.toString())
		expect("garbage input", "0", ADMDeclarations.compute("}}}{{{").size.toString())
		expect("unterminated decl", "1", ADMDeclarations.compute("module m {").size.toString())

		if (failures == 0) {
			println("ALL PASS")
		} else {
			println("$failures FAILURE(S)")
			throw IllegalStateException("$failures failure(s)")
		}
	}

	private fun check(label: String, source: String, expected: String) {
		val sb = StringBuilder()
		for (node in ADMDeclarations.compute(source)) render(node, 0, sb)
		expect(label, expected, sb.toString().trimEnd())
	}

	private fun expect(label: String, expected: String, actual: String) {
		if (expected == actual) {
			println("pass: $label")
		} else {
			failures++
			println("FAIL: $label\n--- expected ---\n$expected\n--- actual ---\n$actual")
		}
	}

	private fun render(node: ADMDeclarations.Node, depth: Int, out: StringBuilder) {
		out.append("  ".repeat(depth)).append(node.kind).append(' ').append(node.name).append('\n')
		for (child in node.children) render(child, depth + 1, out)
	}
}
