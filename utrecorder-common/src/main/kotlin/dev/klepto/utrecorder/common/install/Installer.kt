package dev.klepto.utrecorder.common.install

/**
 * A dependency that materializes itself on disk, and reports whether it already has.
 */
interface Installer {
    /**
     * Materializes the dependency on disk.
     */
    suspend fun install()

    /**
     * Removes what [install] materialized.
     */
    suspend fun uninstall()

    /**
     * `true` if the dependency is materialized on disk.
     */
    suspend fun isInstalled(): Boolean
}
