package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.log.mergeAndThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.exists

/**
 * @author Augustinas R. <http://github.com/klepto>
 */
class PathRegistry(
    private val prefix: String,
    val directory: Path,
) {
    private val paths = mutableSetOf<Path>()

    fun getAll(): Set<Path> = paths.filter { it.exists() }.toSet()

    fun getRoots(): Set<Path> {
        val existing = getAll().map { it.toAbsolutePath().normalize() }
        return existing.filterTo(mutableSetOf()) { path -> existing.none { it != path && path.startsWith(it) } }
    }

    /**
     * The location of [path] relative to [directory], or only its file name when [path] lies outside [directory].
     */
    fun relativize(path: Path): Path {
        val base = directory.toAbsolutePath().normalize()
        val absolute = path.toAbsolutePath().normalize()
        return when {
            absolute.startsWith(base) -> base.relativize(absolute)
            else -> absolute.fileName
        }
    }

    fun register(path: Path) = apply { paths.add(path) }

    fun newFile(name: String) = directory["$prefix$name"].also(::register)

    suspend fun tempFile(): Path = withContext(Dispatchers.IO) { createTempFile(directory, prefix).also(::register) }

    suspend fun tempDirectory(): Path = withContext(Dispatchers.IO) { createTempDirectory(directory, prefix).also(::register) }

    suspend fun delete() =
        withContext(Dispatchers.IO) {
            getRoots()
                .mapNotNull { it.deleteCatching().exceptionOrNull() }
                .mergeAndThrow()
        }
}
