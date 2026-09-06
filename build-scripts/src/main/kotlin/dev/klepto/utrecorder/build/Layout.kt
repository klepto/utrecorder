package dev.klepto.utrecorder.build

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.Directory

/**
 * Registers one element for every directory under [sourceDirectory], named after the directory.
 *
 * Both toolchain plugins take their units from the layout - a directory is an UnrealScript package or a native binary
 * - so a module that follows the layout configures nothing. Listed eagerly, because the task of a unit has to exist
 * by the time the task graph is built, and sorted so that the build is the same whatever order the filesystem lists.
 */
internal fun <T : Any> NamedDomainObjectContainer<T>.createFromLayout(sourceDirectory: Directory) {
    sourceDirectory.asFile
        .listFiles()
        .orEmpty()
        .filter { it.isDirectory }
        .sortedBy { it.name }
        .forEach { maybeCreate(it.name) }
}
