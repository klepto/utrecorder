package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * The configuration files the recording passes play under, carried as resources of the recorder: the game and user
 * configurations a pass's run starts from, and the Bunny Track HUD layout [install] writes into an installation.
 *
 * A pass writes its own values over [gameIni] — the client and console it runs through, its viewport and its frame
 * rate — and takes [userIni] as it is.
 */
object RecorderSettings {
    /**
     * The resource directory the settings sit in, relative to this class.
     */
    private const val DIRECTORY = "settings"

    /**
     * The name of the game configuration resource.
     */
    private const val GAME_INI = "UnrealTournament.ini"

    /**
     * The name of the user configuration resource.
     */
    private const val USER_INI = "User.ini"

    /**
     * The name of the Bunny Track HUD layout, as a resource and in the installation's `System` directory.
     */
    private const val HUD_LAYOUT_INI = "UTBT_HudLayout.ini"

    /**
     * The game configuration a pass's run starts from.
     */
    val gameIni: String by lazy { read(GAME_INI) }

    /**
     * The user configuration a pass's run starts from.
     */
    val userIni: String by lazy { read(USER_INI) }

    /**
     * Writes the Bunny Track HUD layout into the `System` directory of [installation], over whatever the installation
     * held there, and returns the file written.
     */
    suspend fun install(installation: UtInstallation): Path {
        val path = installation.systemDirectory[HUD_LAYOUT_INI]
        withContext(Dispatchers.IO) { path.writeText(read(HUD_LAYOUT_INI), UtPlatform.CHARSET) }
        return path
    }

    /**
     * Returns the text of the settings resource [name].
     */
    private fun read(name: String): String {
        val stream = javaClass.getResourceAsStream("$DIRECTORY/$name") ?: error("Recorder settings not found at $name")
        return stream.use { it.readBytes().toString(UtPlatform.CHARSET) }
    }
}
