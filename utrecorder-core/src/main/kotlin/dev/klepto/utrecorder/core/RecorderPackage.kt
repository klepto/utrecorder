package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.io.EmbeddedArtifacts
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.ut99.UtIni
import dev.klepto.utrecorder.ut99.UtInstallation
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * The UnrealScript package of the recorder, `UTRecorder.u`, written into the `System` directory of an installation so
 * that the console class the runs name as `[Engine.Engine] Console` resolves when the game loads it, beside the
 * settings file that console reads.
 *
 * The package holds the console the runs play their demos through — it exits the process when the demo ends, reports
 * the start and end marks the recorder reads out of the log, and holds the exec functions the passes call — beside the
 * HUD drawing the recorded frames. It does nothing until [configure] hands it its settings: `bAutoExit` is off in the
 * class itself, so an installation the recorder provisioned stays harmless to play in.
 *
 * @property installation The installation the package is written into.
 */
class RecorderPackage(
    private val installation: UtInstallation,
) {
    /**
     * The settings file the installed console reads in the installation's `System` directory.
     */
    private val iniPath: Path = installation.systemDirectory[INI_NAME]

    /**
     * Writes every artifact of the package into the installation's `System` directory, over whatever an earlier run
     * left there, and returns the paths written.
     */
    fun install(): List<Path> = ARTIFACTS.extractTo(installation.systemDirectory)

    /**
     * Writes [ini] as the settings the installed console reads and returns the file written, so that the runs after
     * it play through the recorder console.
     */
    fun configure(ini: UtIni): Path = iniPath.also { ini.write(it) }

    /**
     * Removes the settings file of the installed console from the installation, so that a game the recorder did not
     * launch is not configured by a run that has ended.
     */
    fun clear() {
        iniPath.deleteIfExists()
    }

    companion object {
        /**
         * The name the settings file of the installed console carries, the name of the package it configures.
         */
        const val INI_NAME = "UTRecorder.ini"

        /**
         * The UnrealScript artifacts the uscript module embeds in its jar.
         */
        private val ARTIFACTS = EmbeddedArtifacts(NAMESPACE)

        /**
         * The namespace the UnrealScript artifacts sit in.
         */
        private const val NAMESPACE = "unrealscript"
    }
}
