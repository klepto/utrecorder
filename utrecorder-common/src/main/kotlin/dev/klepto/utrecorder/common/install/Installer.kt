package dev.klepto.utrecorder.common.install

/**
 * Represents an installation procedure.
 *
 * @author Augustinas R. <http://github.com/klepto>
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
 * Installer that runs a sequence of other installers.
 */
data class SequenceInstaller(
    val installers: List<Installer>,
) : Installer {
    override fun install() = installers.forEach { it.install() }

    override fun uninstall() = installers.forEach { it.uninstall() }

    override fun isInstalled() = installers.all { it.isInstalled() }
}
