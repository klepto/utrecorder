package dev.klepto.utrecorder.build

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.Directory

/**
 * Registers one element for every directory under [sourceDirectory], named after the directory. The directories are
 * listed eagerly and in name order, so that the elements exist before the task graph is built and a build does not
 * depend on the order the filesystem lists them in.
 */
internal fun <T : Any> NamedDomainObjectContainer<T>.createFromLayout(sourceDirectory: Directory) {
    sourceDirectory.asFile
        .listFiles()
        .orEmpty()
        .filter { it.isDirectory }
        .sortedBy { it.name }
        .forEach { maybeCreate(it.name) }
}
