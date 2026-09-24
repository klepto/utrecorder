package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.Installer
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.ut99.UtDirectory.Maps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Installer that decompresses the `.uz` map archives in the maps directory of an installation with UCC.
 *
 * Each archive becomes one plain map named after the archive without its `.uz` suffix. The archive is removed once
 * its map exists. An archive whose map already exists is left as it is.
 *
 * @property installation the installation whose maps directory holds the archives.
 */
class UtMapInstaller(
    private val installation: UtInstallation,
) : Installer {
    private val mapsDirectory: Path = installation.directory[Maps]

    override suspend fun install() {
        for (archive in compressed()) {
            install(archive)
        }
    }

    override suspend fun uninstall() {
        val archives = compressed()
        withContext(Dispatchers.IO) {
            archives.forEach { map(it).deleteIfExists() }
        }
    }

    override suspend fun isInstalled(): Boolean = compressed().isEmpty()

    /**
     * Decompresses [archive] into the maps directory and returns the map it wrote. The archive stays in place.
     */
    suspend fun extract(archive: Path): Path = installation.compiler.extract(archive, map(archive))

    /**
     * Returns the `.uz` archives in the maps directory. If the maps directory does not exist, returns an empty list
     * instead.
     */
    suspend fun compressed(): List<Path> =
        withContext(Dispatchers.IO) {
            when {
                mapsDirectory.isDirectory() -> mapsDirectory.listDirectoryEntries().filter(::isArchive)
                else -> emptyList()
            }
        }

    private suspend fun install(archive: Path) {
        val mapExists = withContext(Dispatchers.IO) { map(archive).exists() }
        if (mapExists) return
        extract(archive)
        withContext(Dispatchers.IO) { archive.deleteIfExists() }
    }

    private fun map(archive: Path): Path = mapsDirectory[archive.name.removeSuffix(ARCHIVE_SUFFIX)]

    private companion object {
        const val ARCHIVE_SUFFIX = ".uz"

        fun isArchive(path: Path): Boolean = path.name.endsWith(ARCHIVE_SUFFIX, ignoreCase = true)
    }
}
