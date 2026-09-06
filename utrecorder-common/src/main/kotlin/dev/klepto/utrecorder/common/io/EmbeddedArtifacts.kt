package dev.klepto.utrecorder.common.io

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

/**
 * Reads the build outputs a module embeds in its jar under a [namespace] directory, so that a module built by an
 * external toolchain - the UnrealScript compiler, a C compiler - can hand its outputs to the modules that consume it
 * through the classpath rather than through the filesystem.
 *
 * A namespace holds an [INDEX] file naming the artifacts beside it, one per line. The index is what makes the contents
 * enumerable: a classpath cannot be listed, so without it a consumer would have to know every file name up front.
 *
 * Artifacts that differ per platform are published as classifier variants of the same module rather than as separate
 * entries here, so a namespace always holds exactly the artifacts of the platform the consumer resolved.
 *
 * @param namespace The directory the artifacts sit in, both in the jar and in the index resource path.
 * @param classLoader The class loader the artifacts are read from.
 * @author Augustinas R. <http://github.com/klepto>
 */
class EmbeddedArtifacts(
    private val namespace: String,
    private val classLoader: ClassLoader = EmbeddedArtifacts::class.java.classLoader,
) {
    /**
     * The names of the artifacts this namespace holds, in the order the index lists them. Every index on the classpath
     * contributes, so a namespace assembled from more than one module reads as one.
     */
    val names: List<String> by lazy {
        classLoader
            .getResources("$namespace/$INDEX")
            .asSequence()
            .flatMap {
                it
                    .openStream()
                    .use { stream -> stream.readBytes() }
                    .decodeToString()
                    .lineSequence()
            }.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    /**
     * Reads the artifact of the given [name].
     */
    fun read(name: String): ByteArray {
        val path = "$namespace/$name"
        val stream = classLoader.getResourceAsStream(path) ?: error("Embedded artifact not found at $path")
        return stream.use { it.readBytes() }
    }

    /**
     * Writes every artifact this namespace holds into [directory], overwriting the files already there, and returns
     * the paths written.
     */
    fun extractTo(directory: Path): List<Path> {
        directory.createDirectories()
        return names.map { name ->
            directory[name].also { path ->
                path.parent?.createDirectories()
                path.writeBytes(read(name))
            }
        }
    }

    companion object {
        /**
         * The name of the file naming the artifacts of a namespace.
         */
        const val INDEX = "index"
    }
}
