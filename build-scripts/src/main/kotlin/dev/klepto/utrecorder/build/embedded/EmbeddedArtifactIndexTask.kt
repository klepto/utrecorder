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
 * Task that writes an index file naming every file in a set of artifact directories.
 */
@CacheableTask
abstract class EmbeddedArtifactIndexTask : DefaultTask() {
    /**
     * Directories holding the artifacts to index.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifacts: ConfigurableFileCollection

    /**
     * Index file to write.
     */
    @get:OutputFile
    abstract val index: RegularFileProperty

    /**
     * Writes the path of every file in [artifacts] to [index], one path per line, in sorted order. Each path is
     * relative to the directory that holds the file.
     */
    @TaskAction
    fun generate() {
        val names = buildList { artifacts.asFileTree.visit { if (!isDirectory) add(relativePath.pathString) } }
        index.get().asFile.writeText(names.sorted().joinToString(separator = "\n", postfix = "\n"))
    }
}
