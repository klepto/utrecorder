package dev.klepto.utrecorder.build.embedded

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property

/**
 * The name the index of a namespace takes. Mirrors `EmbeddedArtifacts.INDEX`, which the build cannot reach - the
 * module holding it is built by this build, not by the build that runs these plugins.
 */
const val EMBEDDED_ARTIFACT_INDEX = "index"

/**
 * Configures the build outputs a module embeds in its jar. A toolchain plugin adds its compile task outputs to
 * [artifacts], which land in the jar under [namespace] together with an index naming them.
 */
abstract class EmbeddedArtifactsExtension {
    /**
     * The directory the artifacts take in the jar, and the name the consumer reads them back under.
     */
    abstract val namespace: Property<String>

    /**
     * The directories holding the artifacts to embed. Producers add their task outputs here, which carries the task
     * dependency with it.
     */
    abstract val artifacts: ConfigurableFileCollection
}
