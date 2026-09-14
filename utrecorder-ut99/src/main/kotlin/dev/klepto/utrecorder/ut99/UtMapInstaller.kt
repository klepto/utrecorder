package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.install.Installer
import java.nio.file.Path

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
    override fun install() = TODO()

    /**
     * Removes every plain map the maps directory holds that a compressed archive named.
     */
    override fun uninstall() = TODO()

    /**
     * Returns `true` when the maps directory holds no compressed archive.
     */
    override fun isInstalled(): Boolean = TODO()

    /**
     * Decompresses the archive [archive] into the maps directory and returns the map written.
     */
    fun extract(archive: Path): Path = TODO()

    /**
     * The archives in the maps directory, a map existing for one leaving it nothing to do.
     */
    fun compressed(): List<Path> = TODO()
}
