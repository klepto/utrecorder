package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Represents the Unreal Tournament installation. Gives access to its directories and assets.
 *
 * @param directory The root of the installation.
 * @param workspace The workspace anything the installation runs uses for its tasks.
 * @author Augustinas R. <http://github.com/klepto>
 */
class UtInstallation(
    val directory: Path,
    private val workspace: Workspace = Workspace.DEFAULT,
) {
    /**
     * The UT installation platform.
     */
    val platform = UT_PLATFORM

    /**
     * The UT installation executable. Sits in the system directory, next to the compiler, not at the root.
     */
    val executablePath = directory[UtDirectory.System][platform.gameExecutable]

    /**
     * The UT compiler utility.
     */
    val compiler by lazy {
        UtCompiler(directory[UtDirectory.System][platform.compilerExecutable], workspace)
    }

    init {
        check(directory.exists()) { "UT installation directory not found at $directory" }
        check(executablePath.exists()) { "UT executable not found at $executablePath" }
    }
}
