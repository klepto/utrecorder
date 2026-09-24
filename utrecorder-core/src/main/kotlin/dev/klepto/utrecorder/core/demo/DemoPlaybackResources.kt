package dev.klepto.utrecorder.core.demo

import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.ut99.UtInstallation
import dev.klepto.utrecorder.ut99.UtPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Files for demo playback, carried as resources of the recorder.
 *
 * Read the game and user configurations through [gameIni] and [userIni]. Write the Bunny Track HUD layout into an
 * installation through [installHudLayout].
 */
object DemoPlaybackResources {
    /**
     * The name of the Bunny Track HUD layout, as a resource and in the installation's `System` directory.
     */
    private const val HUD_LAYOUT_INI = "UTBT_HudLayout.ini"

    /**
     * The text of the game configuration, `UnrealTournament.ini`.
     */
    val gameIni: String by lazy { read("UnrealTournament.ini") }

    /**
     * The text of the user configuration, `User.ini`.
     */
    val userIni: String by lazy { read("User.ini") }

    /**
     * Returns the Bunny Track HUD layout file, written into the `System` directory of [installation].
     *
     * Replaces the file that the installation already held there.
     */
    suspend fun installHudLayout(installation: UtInstallation): Path {
        val path = installation.systemDirectory[HUD_LAYOUT_INI]
        withContext(Dispatchers.IO) { path.writeText(read(HUD_LAYOUT_INI), UtPlatform.CHARSET) }
        return path
    }

    /**
     * Returns the text of the playback resource [name].
     */
    private fun read(name: String): String {
        val stream = javaClass.getResourceAsStream("/playback/$name") ?: error("Playback resource not found at $name")
        return stream.use { it.readBytes().toString(UtPlatform.CHARSET) }
    }
}
