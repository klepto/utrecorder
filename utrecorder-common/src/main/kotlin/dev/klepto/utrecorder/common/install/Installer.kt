package dev.klepto.utrecorder.common.install

/**
 * A dependency that materializes itself on disk, and reports whether it already has.
 */
interface Installer {
    /**
     * Runs the installation procedure.
     */
    fun install()

    /**
     * Runs the uninstallation procedure.
     */
    fun uninstall()

    /**
     * Checks if the installation is complete.
     */
    fun isInstalled(): Boolean

    /**
     * Combines two installers into a sequence.
     */
    operator fun plus(other: Installer): Installer = SequenceInstaller(listOf(this, other))
}

/**
 * The installer produced by [Installer.plus], running the [installers] it holds in order.
 *
 * @property installers The installers this one runs, in the order they were combined.
 */
data class SequenceInstaller(
    val installers: List<Installer>,
) : Installer {
    override fun install() = installers.forEach { it.install() }

    override fun uninstall() = installers.forEach { it.uninstall() }

    override fun isInstalled() = installers.all { it.isInstalled() }
}
