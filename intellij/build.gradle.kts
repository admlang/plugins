plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "org.adm.intellij"

// The plugin version lives in VERSION (major.minor.build) and the build
// number goes up on every packaging build -- `buildPlugin`, `runIde`,
// `publishPlugin` -- the way build.sh bumps the compiler's VERSION. A plain
// compile leaves it alone. The file is committed so the number is monotonic
// across machines; the IDE shows it in Settings > Plugins and the zip is
// named after it.
val pluginVersionFile = layout.projectDirectory.file("VERSION").asFile

fun readPluginVersion(): String =
    pluginVersionFile.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } ?: "0.1.0"

fun bumpPluginVersion(): String {
    val parts = readPluginVersion().split(".").toMutableList()
    while (parts.size < 3) parts.add("0")
    parts[parts.lastIndex] = ((parts.last().toIntOrNull() ?: 0) + 1).toString()
    val next = parts.joinToString(".")
    pluginVersionFile.writeText(next + "\n")
    return next
}

val packagingTasks = setOf("buildPlugin", "runIde", "publishPlugin", "signPlugin")
val isPackagingBuild = gradle.startParameter.taskNames.any { name -> packagingTasks.any { name.endsWith(it) } }

version = if (isPackagingBuild) bumpPluginVersion() else readPluginVersion()

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
    testImplementation(kotlin("test"))
    intellijPlatform {
        intellijIdea("2025.3")
        bundledPlugin("com.intellij.copyright")
    }
    runtimeOnly(project(":cidr"))
}

tasks {
    patchPluginXml {
        pluginVersion.set(project.version.toString())
        sinceBuild.set("252")
        untilBuild.set("")
    }
    buildSearchableOptions {
        enabled = false
    }
    test {
        useJUnitPlatform()
    }
    wrapper {
        gradleVersion = "8.7"
    }
}

// Runs ADMDeclarationsSelfCheck: the declaration scanner is pure text -> tree,
// but the IntelliJ test instrumentation cannot start a plain JUnit executor,
// so it is exercised through JavaExec against the main runtime classpath.
tasks.register<JavaExec>("selfCheck") {
    group = "verification"
    mainClass.set("org.adm.intellij.structure.ADMDeclarationsSelfCheck")
    // The IntelliJ platform jars are compile-only, so the runtime classpath
    // alone cannot load LexerBase/Key.
    classpath = sourceSets["main"].output + sourceSets["main"].compileClasspath
}
