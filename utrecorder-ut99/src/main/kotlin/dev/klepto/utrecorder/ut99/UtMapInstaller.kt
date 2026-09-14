package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.install.Installer
import dev.klepto.utrecorder.common.io.get
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Decompression of the compressed maps of an installation: the `.uz` archives in the maps directory extracted with
 * UCC, one plain map per archive, the archive removed once its map exists.
 */
class UtMapInstaller(
    private val installation: UtInstallation,
) : Installer {
    /**
     * Decompresses every archive the maps directory holds that names no map yet.
     */
    override fun install() {
        for (archive in compressed()) {
            installOnce(archive)
        }
    }

    /**
     * Removes every plain map the maps directory holds that a compressed archive named.
     */
    override fun uninstall() {
        for (archive in compressed()) {
            installation.directory[UtDirectory.Maps][mapName(archive)].deleteIfExists()
        }
    }

    /**
     * Returns `true` when the maps directory holds no compressed archive.
     */
    override fun isInstalled() = compressed().isEmpty()

    /**
     * Decompresses the archive [archive] into the maps directory and returns the map written.
     */
    fun extract(archive: Path): Path = installation.compiler.extract(archive, installation.directory[UtDirectory.Maps][mapName(archive)])

    /**
     * The archives in the maps directory, a map existing for one leaving it nothing to do.
     */
    fun compressed(): List<Path> {
        val mapsDirectory = installation.directory[UtDirectory.Maps]
        if (!mapsDirectory.isDirectory()) return emptyList()
        return mapsDirectory.listDirectoryEntries().filter { it.name.endsWith(ARCHIVE_SUFFIX, ignoreCase = true) }
    }

    private fun installOnce(archive: Path) {
        val mapName = mapName(archive)
        if (installation.directory[UtDirectory.Maps][mapName].exists()) return
        extract(archive)
        archive.deleteIfExists()
    }

    private fun mapName(archive: Path) = archive.name.removeSuffix(ARCHIVE_SUFFIX)

    private companion object {
        private const val ARCHIVE_SUFFIX = ".uz"
    }
}
