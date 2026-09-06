package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.toAbsolutePath
import dev.klepto.utrecorder.common.log.logger
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Command line entry point of the Unreal Tournament module, provisioning an installation and driving [UtCompiler].
 *
 * It exists so that a process that cannot depend on this module - the Gradle build, which resolves it as an artifact
 * rather than a classpath - can still reach it, by forking a JVM against the module runtime classpath. Everything it
 * does is already available as an API; nothing here is logic of its own.
 *
 * ```
 * UtCli install [--install=<dir>]
 * UtCli make --sources=<dir> --output=<file> [--jobs=<dir>] [--install=<dir>] [--edit-packages=A,B,C]
 * ```
 *
 * `--install` names the installation to provision and compile with, so that callers that cannot share one - concurrent
 * builds, say - can each hold their own. It defaults to the installation [UtConfig.DEFAULT] names.
 *
 * @param arguments The command line arguments, the command first and its options after it.
 * @author Augustinas R. <http://github.com/klepto>
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
    private val options =
        arguments
            .filter { it.startsWith(OPTION_PREFIX) }
            .associate { option ->
                val entry = option.removePrefix(OPTION_PREFIX)
                entry.substringBefore('=') to entry.substringAfter('=', "")
            }

    /**
     * The configuration the command runs against, holding the installation the `--install` option names.
     */
    private val config = path("install")?.let { UtConfig.DEFAULT.copy(gameDirectory = it) } ?: UtConfig.DEFAULT

    /**
     * The installation the command runs against.
     */
    private val installDirectory = config.gameDirectory

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
        return UtInstallation(installDirectory)
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
