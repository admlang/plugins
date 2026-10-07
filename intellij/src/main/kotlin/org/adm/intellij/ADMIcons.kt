package org.adm.intellij

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Icons for ADM declarations.
 *
 * Each kind gets a coloured letter badge (`T` type, `C` component, `c`
 * constant, `l` variable, …) rather than a borrowed platform icon, so a glance
 * at a tree or a popup says what kind of declaration a row is. Colour groups
 * related kinds: blue for types, green for interfaces, violet for callables,
 * teal for members, amber for constants and enums, magenta for UI declarations.
 *
 * The fields are `@JvmField` on purpose: IntelliJ's documentation renderer
 * resolves `<icon src="org.adm.intellij.ADMIcons.TYPE">` against a static
 * field, which a plain Kotlin `val` inside an `object` is not.
 */
object ADMIcons {
	@JvmField val FILE: Icon = load("adm1")

	@JvmField val TYPE: Icon = load("adm_type")
	@JvmField val STRUCT: Icon = load("adm_struct")
	@JvmField val DATATYPE: Icon = load("adm_datatype")
	@JvmField val INTERFACE: Icon = load("adm_interface")
	@JvmField val ENUM: Icon = load("adm_enum")
	@JvmField val ENUM_VALUE: Icon = load("adm_enumvalue")
	@JvmField val UNION: Icon = load("adm_union")
	@JvmField val UNION_VARIANT: Icon = load("adm_unionvariant")
	@JvmField val COMPONENT: Icon = load("adm_component")
	@JvmField val VIEW: Icon = load("adm_view")
	@JvmField val STYLE: Icon = load("adm_style")
	@JvmField val SERVICE: Icon = load("adm_service")
	@JvmField val MODULE: Icon = load("adm_module")
	@JvmField val APPLICATION: Icon = load("adm_application")
	@JvmField val LIBRARY: Icon = load("adm_library")
	@JvmField val PLUGIN: Icon = load("adm_plugin")
	@JvmField val CHECK: Icon = load("adm_check")
	@JvmField val FUNCTION: Icon = load("adm_function")
	@JvmField val METHOD: Icon = load("adm_method")
	@JvmField val PROPERTY: Icon = load("adm_property")
	@JvmField val FIELD: Icon = load("adm_field")
	@JvmField val CONSTANT: Icon = load("adm_constant")
	@JvmField val VARIABLE: Icon = load("adm_variable")
	@JvmField val PARAMETER: Icon = load("adm_parameter")

	/** `meta` declarations, which carry no distinct symbol kind of their own. */
	@JvmField val META: Icon = load("adm_meta")

	private fun load(name: String): Icon = IconLoader.getIcon("/icons/$name.svg", ADMIcons::class.java)
}
