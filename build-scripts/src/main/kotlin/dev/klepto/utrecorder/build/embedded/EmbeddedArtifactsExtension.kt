package dev.klepto.utrecorder.build.embedded

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property

/**
 * File name of the index that names the artifacts of a namespace.
 */
const val EMBEDDED_ARTIFACT_INDEX = "index"

/**
 * Build outputs a module embeds in its jar, under [namespace] beside an index naming them.
 *
 * The `utrecorder.embedded-artifacts` plugin registers it as `embeddedArtifacts`.
 */
abstract class EmbeddedArtifactsExtension {
    /**
     * Directory in the jar that holds the artifacts and their index.
     */
    abstract val namespace: Property<String>

    /**
     * Directories whose contents are embedded. Adding a task output here also adds a dependency on that task.
     */
    abstract val artifacts: ConfigurableFileCollection
}
