import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
	kotlin("jvm") version "2.1.0"
	id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "org.adm.intellij"
version = "0.1.0"

repositories {
	mavenCentral()
	intellijPlatform {
		defaultRepositories()
	}
}

kotlin {
	jvmToolchain(17)
}

dependencies {
	intellijPlatform {
		// We compile the debugger bridge against CLion to get the Cidr debugger APIs.
		create(IntelliJPlatformType.CLion, "2025.3")
		bundledPlugins("com.intellij.nativeDebug")
		bundledModules("intellij.clion.execution", "intellij.cidr.workspaceModel")
	}
}

tasks {
	// This submodule is a runtime jar bundled into the main ADM plugin, not a standalone plugin.
	// Disable searchable-options generation which expects a META-INF/plugin.xml.
	buildSearchableOptions {
		enabled = false
	}
	prepareJarSearchableOptions {
		enabled = false
	}
	jarSearchableOptions {
		enabled = false
	}
}
