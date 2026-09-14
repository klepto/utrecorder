package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.deleteCatching
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.common.process.Executable
import dev.klepto.utrecorder.common.process.ExecutableResult
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

/**
 * The UCC utility of an installation, compiling UnrealScript packages and decompressing `.uz` archives.
 *
 * Use [UtInstallation.compiler] for the compiler of an installation.
 *
 * @property installation The installation the compiler belongs to.
 * @property directories The directories holding the workspace a compile runs in.
 * @throws IllegalStateException if [installation] holds no compiler executable.
 */
class UtCompiler(
    private val installation: UtInstallation,
    private val directories: Directories = Directories.DEFAULT,
) {
    /**
     * The directory holding the compiler executable, where the compiler reads and writes packages.
     */
    private val directory: Path = installation.systemDirectory

    /**
     * The path of the compiler executable.
     */
    private val executablePath: Path = directory[installation.platform.compilerExecutable]

    init {
        check(executablePath.exists()) { "UT compiler executable not found at $executablePath" }
    }

    /**
     * Runs the compiler with the given [arguments] and returns the result without inspecting its exit code.
     */
    private fun run(vararg arguments: String): ExecutableResult =
        Executable
            .build(executablePath.toAbsolutePath()) {
                workingDirectory = directory
                prependLibraryPath(directory)
                this.arguments += arguments.toList() + installation.platform.homeArguments
            }.startAndAwait()

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
     * The name of [sourcesPath] is the package name, and the directory holds the `Classes` directory the compiler reads
     * the sources from. The configuration is the default configuration of the installation with [configure] applied on
     * top of it and the `EditPackages` entry of the compiled package appended last.
     *
     * @throws IllegalStateException if [sourcesPath] is not a directory, or if the compiler fails.
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
        val stagedPath = installation.directory[packageName]
        val producedPath = directory[packageFileName]

        return workspace("ucc", directories = directories) {
            val iniPath =
                defaultConfig()
                    .apply(configure)
                    .apply { add(EDITOR_SECTION, EDIT_PACKAGES, packageName) }
                    .write(file(MAKE_INI))

            val result =
                try {
                    stagedPath.deleteRecursively()
                    sourcesPath.copyToRecursively(stagedPath, followLinks = false, overwrite = true)
                    producedPath.deleteIfExists()
                    run("make", "-nobind", "-bytehax", "-packages=$packageName", "-ini=${iniPath.absolutePathString()}")
                } finally {
                    stagedPath.deleteCatching()
                }

            file(MAKE_LOG).writeText(result.output.joinToString(System.lineSeparator()))
            result.orThrow()

            check(producedPath.exists()) { "UCC failed to compile $packageName, see ${file(MAKE_LOG)}" }
            producedPath.move(file(packageFileName))

            file(packageFileName).copy(outputPath, overwrite = true)
        }
    }

    /**
     * Reads the default configuration of the installation.
     */
    private fun defaultConfig(): UtIni {
        val defaultPath = installation.defaultIniPath
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
         * The name the compiler configuration of a run is written under.
         */
        private const val MAKE_INI = "make.ini"

        /**
         * The name the compiler output of a run is written under.
         */
        private const val MAKE_LOG = "make.log"
    }
}
