package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.toAbsolutePath
import dev.klepto.utrecorder.common.log.logger
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Command line entry point of the Unreal Tournament module, provisioning an installation and driving [UtCompiler].
 *
 * ```
 * UtCli install [--install=<dir>]
 * UtCli make --sources=<dir> --output=<file> [--jobs=<dir>] [--install=<dir>] [--edit-packages=A,B,C]
 * ```
 *
 * `--install` names the installation to provision and compile with, so that callers that cannot share one - concurrent
 * builds, say - can each hold their own. It defaults to the installation [UtConfig.DEFAULT] names.
 *
 * `--jobs` names the directory the run writes its configuration and its compiler log under, so that a caller driving
 * the compiler - a build, say - collects the diagnostics of a failed run beside the output it was producing. It
 * defaults to [Workspace.DEFAULT]. Either way only a failed run leaves anything behind.
 *
 * @property arguments The command line arguments, the command first and its options after it.
 */
class UtCli(
    private val arguments: List<String>,
) {
    /**
     * Logger for this class.
     */
    private val log = logger<UtCli>()

    /**
     * The command to run, or `null` if the arguments name none.
     */
    private val command = arguments.firstOrNull()?.takeUnless { it.startsWith(OPTION_PREFIX) }

    /**
     * The `--key=value` options the arguments hold, by key.
     */
    private val options = parseOptions()

    /**
     * The configuration the command runs against, holding the installation the `--install` option names.
     */
    private val config = path("install")?.let { UtConfig.DEFAULT.copy(gameDirectory = it) } ?: UtConfig.DEFAULT

    /**
     * The installation the command runs against.
     */
    private val installDirectory = config.gameDirectory

    /**
     * The workspace the command runs its tasks in, rooted at the directory the `--jobs` option names.
     */
    private val workspace = path("jobs")?.let { Workspace(directory = it) } ?: Workspace.DEFAULT

    /**
     * Reads the `--key=value` options the [arguments] hold into a map, by key.
     */
    private fun parseOptions(): Map<String, String> =
        arguments
            .filter { it.startsWith(OPTION_PREFIX) }
            .associate(::parseOption)

    /**
     * Splits one `--key=value` [option] into its key and its value, which is empty when the option carries none.
     */
    private fun parseOption(option: String): Pair<String, String> {
        val entry = option.removePrefix(OPTION_PREFIX)
        return entry.substringBefore('=') to entry.substringAfter('=', "")
    }

    /**
     * Runs the command the arguments name and returns the exit code of the process.
     */
    fun run(): Int =
        try {
            when (command) {
                "install" -> install()
                "make" -> make()
                else -> error("Unknown command '$command', expected one of ${COMMANDS.joinToString(", ")}")
            }
            0
        } catch (cause: Exception) {
            log.error(cause) { USAGE }
            1
        }

    /**
     * Provisions the installation, and returns it.
     */
    private fun install(): UtInstallation {
        UtInstaller(config).install()
        return UtInstallation(installDirectory, workspace)
    }

    /**
     * Compiles the package the `--sources` option names into the file the `--output` option names, provisioning the
     * installation it compiles with first.
     */
    private fun make() {
        val sourcesPath = requireNotNull(path("sources")) { "Missing --sources" }
        val outputPath = requireNotNull(path("output")) { "Missing --output" }
        val editPackages = options["edit-packages"].orEmpty().split(',').filter { it.isNotBlank() }

        val compiler = install().compiler
        val configure: UtIni.() -> Unit = {
            editPackages.forEach { add(UtCompiler.EDITOR_SECTION, UtCompiler.EDIT_PACKAGES, it) }
        }

        val result = compiler.make(sourcesPath, outputPath, configure = configure)
        log.info { "Compiled $sourcesPath into $result" }
    }

    /**
     * Returns the absolute path the option [key] holds, or `null` if the arguments hold no such option.
     */
    private fun path(key: String): Path? = options[key]?.takeIf { it.isNotEmpty() }?.toAbsolutePath()

    companion object {
        /**
         * The prefix an option takes, distinguishing it from the command.
         */
        private const val OPTION_PREFIX = "--"

        /**
         * The commands this entry point accepts.
         */
        private val COMMANDS = listOf("install", "make")

        /**
         * The usage this entry point reports when it cannot run the arguments it was given.
         */
        private val USAGE =
            """
            usage: UtCli install [--install=<dir>]
                   UtCli make --sources=<dir> --output=<file> [--jobs=<dir>] [--install=<dir>] [--edit-packages=A,B,C]
            """.trimIndent()

        @JvmStatic
        fun main(args: Array<String>): Unit = exitProcess(UtCli(args.toList()).run())
    }
}
