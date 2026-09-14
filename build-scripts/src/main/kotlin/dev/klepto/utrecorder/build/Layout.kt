package dev.klepto.utrecorder.build

import dev.klepto.utrecorder.build.embedded.EmbeddedArtifactsExtension
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.gradle.language.base.plugins.LifecycleBasePlugin
import kotlin.reflect.KClass

/**
 * Creates one element of this container for every directory under [sourceDirectory], named after the directory and in
 * name order.
 */
fun <T : Any> NamedDomainObjectContainer<T>.createFromLayout(sourceDirectory: Directory) {
    sourceDirectory.asFile
        .listFiles()
        .orEmpty()
        .filter { it.isDirectory }
        .sortedBy { it.name }
        .forEach { maybeCreate(it.name) }
}

/**
 * Registers one element of [elements] per directory under [sourceDirectory], a [taskType] task per element, and the
 * aggregate task [taskName] depending on every task of [taskType].
 *
 * Each element's task is named [taskName] followed by the capitalized element name, is configured by [configure] with
 * the element, and adds its [outputDirectory] to the artifacts of the project's [EmbeddedArtifactsExtension].
 */
fun <E : Named, T : Task> Project.registerLayoutTasks(
    elements: NamedDomainObjectContainer<E>,
    sourceDirectory: Directory,
    taskName: String,
    description: String,
    taskType: KClass<T>,
    outputDirectory: (T) -> Provider<Directory>,
    configure: T.(element: E) -> Unit,
) {
    val embeddedArtifacts = extensions.getByType<EmbeddedArtifactsExtension>()

    tasks.register(taskName) {
        group = LifecycleBasePlugin.BUILD_GROUP
        this.description = description
        dependsOn(tasks.withType(taskType.java))
    }

    elements.createFromLayout(sourceDirectory)
    elements.all {
        val element = this
        val task =
            tasks.register(taskName + element.name.replaceFirstChar(Char::uppercaseChar), taskType.java) {
                group = LifecycleBasePlugin.BUILD_GROUP
                configure(element)
            }
        embeddedArtifacts.artifacts.from(task.flatMap(outputDirectory))
    }
}
