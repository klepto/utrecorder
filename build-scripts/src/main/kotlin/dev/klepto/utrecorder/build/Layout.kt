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
 * Creates one element of this container for every directory under [sourceDirectory], named after the directory.
 *
 * Elements are created in name order. An element that already has the name of a directory is kept as it is.
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
 * Registers a [taskType] task for every directory under [sourceDirectory], and an aggregate task named [taskName].
 *
 * One element of [elements] is created per directory, as [createFromLayout] does. The task of an element is named
 * [taskName] followed by the capitalized element name, and [configure] configures it with that element. The
 * [outputDirectory] of the task is added to the artifacts of the project's [EmbeddedArtifactsExtension].
 *
 * The aggregate task has the given [description] and depends on every [taskType] task of the project. All tasks go
 * in the build group.
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
        val elementTaskName = taskName + element.name.replaceFirstChar(Char::uppercaseChar)
        val task =
            tasks.register(elementTaskName, taskType.java) {
                group = LifecycleBasePlugin.BUILD_GROUP
                configure(element)
            }
        embeddedArtifacts.artifacts.from(task.flatMap(outputDirectory))
    }
}
