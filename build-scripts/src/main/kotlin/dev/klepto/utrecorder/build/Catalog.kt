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
 * The alias the Java toolchain version takes in the version catalogue.
 */
private const val JAVA_VERSION_ALIAS = "java"

/**
 * The alias the detekt tool version takes in the version catalogue.
 */
private const val DETEKT_VERSION_ALIAS = "detekt"

/**
 * The alias the ktlint tool version takes in the version catalogue.
 */
private const val KTLINT_VERSION_ALIAS = "ktlint"

/**
 * The version catalogue every module shares. Reached through the extension rather than through the generated
 * accessors, which a convention plugin does not get - they are generated for the build being configured, not for the
 * build the plugins are compiled in.
 */
private val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Returns the library the catalogue holds under [alias].
 */
internal fun Project.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    libs.findLibrary(alias).orFail("library", alias)

/**
 * Returns the bundle the catalogue holds under [alias].
 */
internal fun Project.bundle(alias: String): Provider<ExternalModuleDependencyBundle> =
    libs.findBundle(alias).orFail("bundle", alias)

/**
 * The Java toolchain every module is compiled with, and every process the build forks runs on.
 *
 * Read from the version catalogue rather than written into each convention plugin, so that a bump is one edit and a
 * forked compiler can never end up on a different JVM than the module it was forked against.
 */
internal val Project.javaVersion: Int
    get() = libs.findVersion(JAVA_VERSION_ALIAS).orFail("version", JAVA_VERSION_ALIAS).requiredVersion.toInt()

/**
 * The detekt tool version the static analysis runs.
 *
 * Pinned in the version catalogue rather than left to the Gradle plugin's own default, so that the plugin and the
 * analyzer it forks are always the same release.
 */
internal val Project.detektVersion: String
    get() = libs.findVersion(DETEKT_VERSION_ALIAS).orFail("version", DETEKT_VERSION_ALIAS).requiredVersion

/**
 * The ktlint tool version the formatter runs.
 *
 * Pinned in the version catalogue rather than left to the Gradle plugin's own default, so that upgrading the plugin
 * never silently reformats the repository.
 */
internal val Project.ktlintVersion: String
    get() = libs.findVersion(KTLINT_VERSION_ALIAS).orFail("version", KTLINT_VERSION_ALIAS).requiredVersion

/**
 * Returns the entry the catalogue holds, or fails naming the [kind] and [alias] that is missing from it.
 */
private fun <T> Optional<T>.orFail(
    kind: String,
    alias: String,
): T = orElseThrow { IllegalStateException("No $kind '$alias' in version catalog") }
