package dev.klepto.utrecorder.ut99

import java.nio.file.Path

/**
 * Represents a running Unreal Tournament instance.
 *
 * @author Augustinas R. <http://github.com/klepto>
 */
class UtInstance(
    private val process: Process,
    private val executablePath: Path,
    private val gameIniPath: Path,
    private val userIniPath: Path,
    private val logPath: Path,
) {
    /**
     * Whether the instance is currently running.
     */
    val isRunning: Boolean
        get() = true

    /**
     * Closes the instance.
     */
    fun close() {
    }
}
