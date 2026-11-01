package dev.klepto.utrecorder.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Source of the usable bytes of a filesystem.
 *
 * [SYSTEM] reads the real disk. A failure to read it throws an `IOException`.
 */
fun interface DiskSpace {
    /** Returns the usable bytes of the filesystem that holds [path], or its nearest existing ancestor. */
    suspend fun usableBytes(path: Path): Long

    companion object {
        /** Reads `FileStore.usableSpace` in `Dispatchers.IO`. */
        val SYSTEM: DiskSpace =
            DiskSpace { path ->
                withContext(Dispatchers.IO) {
                    val absolute = path.toAbsolutePath()
                    val existing = generateSequence(absolute) { it.parent }.firstOrNull { it.exists() } ?: absolute.root
                    Files.getFileStore(existing).usableSpace
                }
            }
    }
}
