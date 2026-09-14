package dev.klepto.utrecorder.build

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependencyBundle
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import java.util.Optional

/**
 * Returns the entry the catalogue holds, or throws [IllegalStateException] naming the [kind] and [alias] missing from it.
 */
private fun <T> Optional<T>.orFail(
    kind: String,
    alias: String,
): T = orElseThrow { IllegalStateException("No $kind '$alias' in version catalog") }

// Reached through the extension because a convention plugin gets no generated accessors: they are generated for the
// build being configured, not for the build the plugins are compiled in.
private val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Returns the library the `libs` version catalogue holds under [alias].
 *
 * @throws IllegalStateException if the catalogue holds no library under [alias].
 */
fun Project.lib(alias: String): Provider<MinimalExternalModuleDependency> = libs.findLibrary(alias).orFail("library", alias)

/**
 * Returns the bundle the `libs` version catalogue holds under [alias].
 *
 * @throws IllegalStateException if the catalogue holds no bundle under [alias].
 */
fun Project.bundle(alias: String): Provider<ExternalModuleDependencyBundle> = libs.findBundle(alias).orFail("bundle", alias)

/**
 * Returns the required version the `libs` version catalogue holds under [alias].
 *
 * @throws IllegalStateException if the catalogue holds no version under [alias].
 */
fun Project.catalogVersion(alias: String): String = libs.findVersion(alias).orFail("version", alias).requiredVersion

/**
 * The Java toolchain version every module compiles with and every forked process runs on.
 */
val Project.javaVersion: Int
    get() = catalogVersion("java").toInt()

/**
 * The ktlint release the formatter runs.
 */
val Project.ktlintVersion: String
    get() = catalogVersion("ktlint")
