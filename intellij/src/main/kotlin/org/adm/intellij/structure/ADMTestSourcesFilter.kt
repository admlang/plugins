package org.adm.intellij.structure

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.TestSourcesFilter
import com.intellij.openapi.vfs.VirtualFile

/**
 * `*_test.adm` files are test sources: the project view colours them the way
 * it colours a test source root, and Search Everywhere's "tests" scope
 * includes them. The convention is the compiler's (`check` suites live in
 * `_test.adm` files beside the module they test).
 */
class ADMTestSourcesFilter : TestSourcesFilter() {
	override fun isTestSource(file: VirtualFile, project: Project): Boolean =
		!file.isDirectory && file.name.endsWith("_test.adm")
}
