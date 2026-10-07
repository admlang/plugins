package org.adm.intellij.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import org.adm.intellij.ADMIcons
import javax.swing.Icon

class ADMFileType private constructor() : LanguageFileType(ADMLanguage) {
    companion object {
        val INSTANCE: ADMFileType = ADMFileType()
    }

    override fun getName(): String = "ADM"

    override fun getDescription(): String = "ADM language source file"

    override fun getDefaultExtension(): String = "adm"

    override fun getIcon(): Icon? = ADMIcons.FILE
}
