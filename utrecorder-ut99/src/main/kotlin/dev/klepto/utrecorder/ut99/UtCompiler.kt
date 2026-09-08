package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.deleteRecursivelyCatching
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.process.ExecutableResult
import dev.klepto.utrecorder.common.process.executable
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

/**
 * The UCC utility of an installation, compiling UnrealScript packages and decompressing `.uz` archives.
 *
 * @property executablePath Path to the UT compiler executable.
 * @property workspace The workspace a compile runs its task in.
 */
class UtCompiler(
    private val executablePath: Path,
    private val workspace: Workspace = Workspace.DEFAULT,
) {
    init {
        check(executablePath.exists()) { "UT compiler executable not found at $executablePath" }
    }

    /**
     * The directory where the compiler executable is located.
     */
    private val directory: Path = executablePath.toAbsolutePath().parent

    /**
     * The root directory of the UT installation the compiler belongs to. Package sources are resolved against it.
     */
    private val rootDirectory: Path = directory.parent

    /**
     * The compiler executable configured with the necessary environment variables.
     */
    private val executable =
        executable(executablePath.toAbsolutePath()) {
            workingDirectory = directory
            prependLibraryPath(directory)
        }

    /**
     * Runs the compiler with the given [arguments] and returns the result without inspecting its exit code.
     */
    private fun run(vararg arguments: String): ExecutableResult =
        executable
            .copy(arguments = arguments.toList() + UT_PLATFORM.homeArguments)
            .startAndAwait()

    /**
     * Extracts the `.uz` archive into given [outputPath]. If [outputPath] is not specified, the archive will be
     * extracted in the same directory as the `.uz` file.
     */
    fun extract(
        uzPath: Path,
        outputPath: Path? = null,
    ): Path {
        val fileName = uzPath.fileName.toString().removeSuffix(".uz")
        val targetPath = outputPath ?: uzPath.toAbsolutePath().parent[fileName]

        run("decompress", uzPath.absolutePathString()).orThrow()
        val resultPath = directory[fileName]
        check(resultPath.exists()) { "UCC failed to extract $uzPath" }
        if (resultPath != targetPath) {
            resultPath.move(targetPath)
        }
        return targetPath
    }

    /**
     * Compiles the UnrealScript package rooted at [sourcesPath] and copies the resulting package to [outputPath].
     *
     * The name of [sourcesPath] is the package name, and the directory is expected to hold the `Classes` directory the
     * compiler reads the sources from. The compiler only resolves packages that sit next to the `System` directory, so
     * the sources are staged into the installation for the duration of the run and removed afterwards.
     *
     * The configuration is seeded with the installation defaults, [configure] is applied on top of it so that the
     * caller can append the packages this one depends on, and the `EditPackages` entry of the compiled package is
     * appended last. `EditPackages` is ordered, so a package only ever compiles against entries that precede it.
     */
    @OptIn(ExperimentalPathApi::class)
    fun make(
        sourcesPath: Path,
        outputPath: Path,
        configure: UtIni.() -> Unit = {},
    ): Path {
        check(sourcesPath.isDirectory()) { "UT package sources not found at $sourcesPath" }

        val packageName = sourcesPath.fileName.toString()
        val packageFileName = "$packageName.u"
        val stagedPath = rootDirectory[packageName]
        val producedPath = directory[packageFileName]

        return workspace.task("ucc") { taskDirectory ->
            val iniPath =
                defaultConfig()
                    .apply(configure)
                    .apply { add(EDITOR_SECTION, EDIT_PACKAGES, packageName) }
                    .write(taskDirectory[MAKE_INI])

            val result =
                try {
                    stagedPath.deleteRecursively()
                    sourcesPath.copyToRecursively(stagedPath, followLinks = false, overwrite = true)
                    producedPath.deleteIfExists()
                    run("make", "-nobind", "-bytehax", "-packages=$packageName", "-ini=${iniPath.absolutePathString()}")
                } finally {
                    stagedPath.deleteRecursivelyCatching()
                }

            taskDirectory[MAKE_LOG].writeText(result.output.joinToString(System.lineSeparator()))
            result.orThrow()

            check(producedPath.exists()) { "UCC failed to compile $packageName, see ${taskDirectory[MAKE_LOG]}" }
            producedPath.move(taskDirectory[packageFileName])

            outputPath.parent?.createDirectories()
            taskDirectory[packageFileName].copyTo(outputPath, overwrite = true)
        }
    }

    /**
     * Reads the installation configuration the compiler defaults to.
     */
    private fun defaultConfig(): UtIni {
        val defaultPath = directory[DEFAULT_INI]
        check(defaultPath.exists()) { "UT default configuration not found at $defaultPath" }
        return UtIni.read(defaultPath)
    }

    companion object {
        /**
         * The configuration section holding the packages the compiler builds.
         */
        const val EDITOR_SECTION = "Editor.EditorEngine"

        /**
         * The configuration key holding a package the compiler builds.
         */
        const val EDIT_PACKAGES = "EditPackages"

        /**
         * The name of the installation configuration the compiler defaults to.
         */
        private const val DEFAULT_INI = "Default.ini"

        /**
         * The name the compiler configuration of a run is written under.
         */
        private const val MAKE_INI = "make.ini"

        /**
         * The name the compiler output of a run is written under.
         */
        private const val MAKE_LOG = "make.log"
    }
}
