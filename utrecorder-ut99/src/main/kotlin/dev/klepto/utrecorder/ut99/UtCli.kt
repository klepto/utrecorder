package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.toAbsolutePath
import dev.klepto.utrecorder.common.log.logger
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Command line entry point of the Unreal Tournament module, provisioning an installation and driving [UtCompiler].
 *
 * Use [main] to run it as a process, or [run] to run it in the current one. `install` provisions the installation, and
 * `make` compiles the package `--sources` names into the file `--output` names. `--install` names the installation,
 * defaulting to the one [UtConfig.DEFAULT] names. `--tasks` names the directory a run writes its tasks under,
 * defaulting to [Workspace.DEFAULT]. `--edit-packages` names the packages a compile compiles against.
 *
 * @property arguments The command line arguments, the command first and its options after it.
 */
class UtCli(
    private val arguments: List<String>,
) {
    /**
     * The name of the command to run, or `null` if the arguments name none.
     */
    private val commandName = arguments.firstOrNull()?.takeUnless { it.startsWith(OPTION_PREFIX) }

    /**
     * The `--key=value` options the arguments hold, by key.
     */
    private val options = parseOptions()

    /**
     * The configuration the command runs against, holding the installation the `--install` option names.
     */
    private val config = path("install")?.let { UtConfig.DEFAULT.copy(gameDirectory = it) } ?: UtConfig.DEFAULT

    /**
     * The workspace the command runs its tasks in, rooted at the directory the `--tasks` option names.
     */
    private val workspace = path("tasks")?.let { Workspace(it, Workspace.DEFAULT.keep, Workspace.DEFAULT.retention) } ?: Workspace.DEFAULT

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
     * Runs the command the arguments name and returns the exit code of the process, `1` when the command fails.
     */
    @Suppress("TooGenericExceptionCaught")
    fun run(): Int =
        try {
            command().run(this)
            0
        } catch (cause: Exception) {
            log.error(cause) { USAGE }
            1
        }

    /**
     * Returns the command the arguments name.
     *
     * @throws IllegalStateException if the arguments name no known command.
     */
    private fun command(): Command =
        Command.entries.firstOrNull { it.commandName == commandName }
            ?: error("Unknown command '$commandName', expected one of ${Command.entries.joinToString(", ") { it.commandName }}")

    /**
     * Provisions the installation, and returns it.
     */
    private fun install(): UtInstallation {
        UtInstaller(config).install()
        return UtInstallation(config.gameDirectory, workspace)
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

    /**
     * A command this entry point runs, by the name the arguments give it.
     */
    private enum class Command(
        val commandName: String,
    ) {
        INSTALL("install") {
            override fun run(cli: UtCli) {
                cli.install()
            }
        },
        MAKE("make") {
            override fun run(cli: UtCli) = cli.make()
        },
        ;

        /**
         * Runs this command against [cli].
         */
        abstract fun run(cli: UtCli)
    }

    companion object {
        /**
         * The prefix an option takes, distinguishing it from the command.
         */
        private const val OPTION_PREFIX = "--"

        /**
         * The usage this entry point reports when it cannot run the arguments it was given.
         */
        private val USAGE =
            """
            usage: UtCli install [--install=<dir>]
                   UtCli make --sources=<dir> --output=<file> [--tasks=<dir>] [--install=<dir>] [--edit-packages=A,B,C]
            """.trimIndent()

        private val log = logger<UtCli>()

        @JvmStatic
        fun main(args: Array<String>): Unit = exitProcess(UtCli(args.toList()).run())
    }
}
