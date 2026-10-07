package org.adm.intellij.structure

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiFile
import org.adm.intellij.ADMIcons
import org.adm.intellij.lang.ADMFileType
import javax.swing.Icon

/**
 * Structure tool window for `.adm` files.
 *
 * Nothing populated it before: the Structure window asks for a
 * `PsiStructureViewFactory` per language and ADM registered none, so the panel
 * stayed empty. The tree is built from [ADMDeclarations] rather than PSI, since
 * the ADM parser definition yields a flat tree.
 */
class ADMStructureViewFactory : PsiStructureViewFactory {
	override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
		if (psiFile.fileType != ADMFileType.INSTANCE) return null
		return object : TreeBasedStructureViewBuilder() {
			override fun createStructureViewModel(editor: Editor?): StructureViewModel =
				ADMStructureViewModel(psiFile)

			override fun isRootNodeShown(): Boolean = false
		}
	}
}

private class ADMStructureViewModel(private val file: PsiFile) :
	StructureViewModelBase(file, ADMFileElement(file)),
	StructureViewModel.ElementInfoProvider {

	init {
		withSorters(Sorter.ALPHA_SORTER)
	}

	override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

	override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
		element is ADMNodeElement && element.node.children.isEmpty()
}

/** Invisible root: its children are the file's top-level declarations. */
private class ADMFileElement(private val file: PsiFile) : StructureViewTreeElement {
	override fun getValue(): Any = file

	override fun getPresentation(): ItemPresentation = object : ItemPresentation {
		override fun getPresentableText(): String = file.name
		override fun getIcon(unused: Boolean): Icon? = ADMFileType.INSTANCE.icon
	}

	override fun getChildren(): Array<StructureViewTreeElement> =
		ADMDeclarations.rootsFor(file).map { ADMNodeElement(file, it) }.toTypedArray()

	override fun navigate(requestFocus: Boolean) {
		(file as? Navigatable)?.navigate(requestFocus)
	}

	override fun canNavigate(): Boolean = (file as? Navigatable)?.canNavigate() ?: false
	override fun canNavigateToSource(): Boolean = canNavigate()
}

private class ADMNodeElement(
	private val file: PsiFile,
	val node: ADMDeclarations.Node,
) : StructureViewTreeElement, SortableTreeElement {

	override fun getValue(): Any = node

	override fun getAlphaSortKey(): String = node.name

	override fun getPresentation(): ItemPresentation = object : ItemPresentation {
		override fun getPresentableText(): String = node.name

		override fun getLocationString(): String? = node.detail.takeIf { it.isNotBlank() }

		// ADM's own letter badges rather than borrowed platform icons, so the
		// tree reads the same way as the rest of the ADM tooling.
		override fun getIcon(unused: Boolean): Icon = when (node.kind) {
			ADMDeclarations.Kind.Application -> ADMIcons.APPLICATION
			ADMDeclarations.Kind.Library -> ADMIcons.LIBRARY
			ADMDeclarations.Kind.Plugin -> ADMIcons.PLUGIN
			ADMDeclarations.Kind.Module -> ADMIcons.MODULE
			ADMDeclarations.Kind.Type -> ADMIcons.TYPE
			ADMDeclarations.Kind.Function -> ADMIcons.FUNCTION
			ADMDeclarations.Kind.Suite -> ADMIcons.CHECK
			ADMDeclarations.Kind.Field -> ADMIcons.FIELD
		}
	}

	override fun getChildren(): Array<StructureViewTreeElement> =
		node.children.map { ADMNodeElement(file, it) }.toTypedArray()

	// Navigation works off the declaration's offset: the flat PSI has no element
	// to delegate to, so move the caret through the file's own descriptor.
	override fun navigate(requestFocus: Boolean) {
		val vf = file.virtualFile ?: return
		com.intellij.openapi.fileEditor.OpenFileDescriptor(file.project, vf, node.offset)
			.navigate(requestFocus)
	}

	override fun canNavigate(): Boolean = file.virtualFile != null
	override fun canNavigateToSource(): Boolean = canNavigate()
}
