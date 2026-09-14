package dev.klepto.utrecorder.common.io

import java.net.URL
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeBytes

/**
 * Build outputs a module carries in its jar under a [namespace] directory, beside an [INDEX] file naming them one per
 * line.
 *
 * Construct one per namespace, then list the artifacts through [names], read one through [read], or write them all to
 * disk through [extractTo]. The index name matches `EmbeddedArtifactsExtension` in `build-scripts`.
 *
 * @property namespace The directory the artifacts sit in, both in the jar and in the index resource path.
 * @property classLoader The class loader the artifacts are read from.
 */
class EmbeddedArtifacts(
    private val namespace: String,
    private val classLoader: ClassLoader = EmbeddedArtifacts::class.java.classLoader,
) {
    /**
     * The names of the artifacts this namespace holds across every index on the classpath, in index order and without
     * duplicates.
     */
    val names: List<String> by lazy { readNames() }

    private fun readNames(): List<String> =
        classLoader
            .getResources("$namespace/$INDEX")
            .asSequence()
            .flatMap(::readIndex)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    private fun readIndex(url: URL): Sequence<String> =
        url
            .openStream()
            .use { it.readBytes() }
            .decodeToString()
            .lineSequence()

    /**
     * The bytes of the artifact called [name] in this namespace.
     *
     * @throws IllegalStateException if the classpath holds no such artifact.
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
            directory[name].also { path -> path.createParentDirectories().writeBytes(read(name)) }
        }
    }

    companion object {
        /**
         * The file name of the index naming the artifacts of a namespace.
         */
        const val INDEX = "index"
    }
}
