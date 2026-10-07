package org.adm.intellij.debugger

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.jetbrains.cidr.execution.debugger.breakpoints.CidrLineBreakpointFileTypesProvider

class ADMLineBreakpointFileTypesProvider : CidrLineBreakpointFileTypesProvider {
	override fun getFileTypes(): Set<FileType> {
		return setOf(FileTypeManager.getInstance().getFileTypeByExtension("adm"))
	}
}
