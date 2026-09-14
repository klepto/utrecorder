package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtPackage

/**
 * The client-package GUID override of one demo: the packages a player may freely swap have their installation copy
 * rewritten to the GUID the demo asks for, so playback resolves them.
 *
 * Server-validated packages are never overridden; a GUID mismatch there is a genuine wrong version.
 */
class ClientPackageOverrides(
    private val installation: UtInstallation,
) {
    /**
     * Rewrites the GUID of the installation's copy of every demo [packages] entry a player may swap, when the demo
     * names one and its GUID differs, and returns the packages overridden.
     */
    fun apply(packages: List<UtPackage>): List<UtPackage> = TODO()

    companion object {
        /**
         * The packages a player may freely swap, matched by name ignoring case.
         */
        val CLIENT_PACKAGES = setOf("announcer")
    }
}
