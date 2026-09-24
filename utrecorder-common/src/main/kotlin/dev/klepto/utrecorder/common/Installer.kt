package dev.klepto.utrecorder.common

/**
 * A sequential script that installs dependencies onto disk and uninstalls them again.
 *
 * Each step of the script runs after the one before it finishes. [install] runs the script forward, [uninstall] removes
 * what it installed, and [isInstalled] reports whether running it would change anything.
 */
interface Installer {
    /**
     * Runs the script, installing each dependency in turn. A dependency already on disk is left as it is.
     *
     * [onProgress] receives how much of the script is done. It is called from any thread, and at least once with
     * `1.0` when the script finishes.
     */
    suspend fun install(onProgress: ProgressListener = {})

    /**
     * Uninstalls the dependencies [install] put on disk.
     */
    suspend fun uninstall()

    /**
     * `true` if every dependency of the script is already on disk.
     */
    suspend fun isInstalled(): Boolean
}
