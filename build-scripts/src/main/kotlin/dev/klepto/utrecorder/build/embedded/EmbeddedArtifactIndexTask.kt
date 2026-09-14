package dev.klepto.utrecorder.build.embedded

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Task writing the index of the embedded artifacts of a module: one path per line, relative to the namespace directory,
 * in sorted order.
 */
@CacheableTask
abstract class EmbeddedArtifactIndexTask : DefaultTask() {
    /**
     * The directories holding the artifacts to index.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifacts: ConfigurableFileCollection

    /**
     * The index file to write.
     */
    @get:OutputFile
    abstract val index: RegularFileProperty

    /**
     * Writes the index of the artifacts this task was given.
     */
    @TaskAction
    fun generate() {
        val names = mutableListOf<String>()
        artifacts.asFileTree.visit { if (!isDirectory) names += relativePath.pathString }
        index.get().asFile.writeText(names.sorted().joinToString(separator = "\n", postfix = "\n"))
    }
}
