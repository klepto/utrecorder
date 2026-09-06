package dev.klepto.utrecorder.build.embedded

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property

/**
 * The name the index of a namespace takes. Mirrors `EmbeddedArtifacts.INDEX`, which the build cannot reach - the
 * module holding it is built by this build, not by the build that runs these plugins.
 */
const val EMBEDDED_ARTIFACT_INDEX = "index"

/**
 * Configures the build outputs a module embeds in its jar, so that a module built by an external toolchain can hand
 * its outputs to the modules that consume it through the classpath rather than through the filesystem.
 *
 * The plugin that owns the toolchain - `utrecorder.unrealscript`, and the C plugin after it - registers its compile
 * tasks and adds their output directories to [artifacts]. Everything from there on is the same for every toolchain:
 * the contents land in the jar under [namespace], an index naming them lands beside them, and
 * `dev.klepto.utrecorder.common.io.EmbeddedArtifacts` reads them back at runtime.
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
