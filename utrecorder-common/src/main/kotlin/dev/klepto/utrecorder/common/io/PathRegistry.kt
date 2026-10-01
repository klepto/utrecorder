package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.mergeAndThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.exists

/**
 * Set of registered paths that are removed together by [delete].
 *
 * Safe to use from concurrent coroutines. Keeps the paths in the order they were registered.
 *
 * @property prefix the prefix of every file name this registry creates.
 * @property directory the directory this registry creates its files in.
 */
class PathRegistry(
    private val prefix: String,
    val directory: Path,
) {
    private val paths: MutableSet<Path> = CopyOnWriteArraySet()

    /**
     * Returns the registered paths that exist.
     */
    suspend fun getAll(): Set<Path> =
        withContext(Dispatchers.IO) {
            paths.filterTo(mutableSetOf()) { it.exists() }
        }

    /**
     * Returns the absolute paths roots of the registered paths that exist.
     */
    suspend fun getRoots(): Set<Path> {
        val existing = getAll().map { it.toAbsolutePath().normalize() }
        return existing.filterTo(mutableSetOf()) { path -> existing.none { it != path && path.startsWith(it) } }
    }

    /**
     * Returns the location of [path] relative to [directory].
     *
     * If [path] lies outside [directory], returns only its file name instead.
     */
    fun relativize(path: Path): Path {
        val base = directory.toAbsolutePath().normalize()
        val absolute = path.toAbsolutePath().normalize()
        return when {
            absolute.startsWith(base) -> base.relativize(absolute)
            else -> absolute.fileName
        }
    }

    /**
     * Registers [path] and returns this registry.
     */
    fun register(path: Path): PathRegistry = apply { paths.add(path) }

    /**
     * Returns the path of the file named [name] after [prefix] in [directory], and registers it. Does not create the
     * file.
     */
    fun newFile(name: String): Path = directory["$prefix$name"].also(::register)

    /**
     * Returns a new empty file in [directory] whose name starts with [prefix], and registers it.
     */
    suspend fun tempFile(): Path =
        withContext(Dispatchers.IO) {
            createTempFile(directory, prefix).also(::register)
        }

    /**
     * Returns a new empty directory in [directory] whose name starts with [prefix], and registers it.
     */
    suspend fun tempDirectory(): Path =
        withContext(Dispatchers.IO) {
            createTempDirectory(directory, prefix).also(::register)
        }

    /**
     * Removes every registered path that exists, together with everything under it.
     *
     * Tries every path before it fails. The first failure is thrown, with every other failure added to it as
     * suppressed.
     */
    suspend fun delete() {
        withContext(Dispatchers.IO) {
            getRoots()
                .mapNotNull { it.deleteCatching().exceptionOrNull() }
                .mergeAndThrow()
        }
    }
}
