package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.Executable
import dev.klepto.utrecorder.common.io.ExecutableResult
import dev.klepto.utrecorder.common.io.copy
import dev.klepto.utrecorder.common.io.deleteCatching
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.isPlainFileName
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.io.startAndAwait
import dev.klepto.utrecorder.common.io.workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

/**
 * The UCC utility of an installation, compiling UnrealScript packages and decompressing `.uz` archives.
 *
 * Use [UtInstallation.compiler] for the compiler of an installation.
 *
 * Compilations and extractions in one `System` directory run one at a time, across every compiler of that directory.
 *
 * @property installation The installation the compiler belongs to.
 * @property config The configuration whose directories the workspaces of the compiler runs are opened under.
 * @throws IllegalStateException if [installation] holds no compiler executable.
 */
class UtCompiler(
    private val installation: UtInstallation,
    private val config: UtConfig = UtConfig(),
) {
    /**
     * The directory holding the compiler executable, where the compiler reads and writes packages.
     */
    private val directory: Path = installation.systemDirectory

    /**
     * The path of the compiler executable.
     */
    private val executablePath: Path = directory[installation.platform.compilerExecutable]

    /**
     * The lock a compilation or an extraction holds while the compiler writes into [directory].
     */
    private val lock: Mutex = LOCKS.computeIfAbsent(directory.toAbsolutePath().normalize()) { Mutex() }

    init {
        check(executablePath.exists()) { "UT compiler executable not found at $executablePath" }
    }

    /**
     * Returns the result of running the compiler with [arguments], without inspecting its exit code.
     *
     * The compiler logs to a file of a workspace opened in [directory] for the run.
     */
    private suspend fun run(vararg arguments: String): ExecutableResult =
        workspace("ucc", directory, config.directoriesConfig) { workspace ->
            Executable
                .build(executablePath.toAbsolutePath()) {
                    workingDirectory = directory
                    prependLibraryPath(directory)
                    this.arguments += arguments.toList()
                    this.arguments += "-log=${workspace["ucc.log"].fileName}"
                    this.arguments += installation.platform.homeArguments
                }.startAndAwait()
        }

    /**
     * Extracts the `.uz` archive at [uzPath] to [outputPath] and returns the path of the extracted file.
     *
     * If [outputPath] is not provided, uses the directory that holds the archive and the archive name without its
     * `.uz` suffix, matched in any case, instead.
     *
     * A run the compiler aborts, exiting with [ABORT_EXIT_CODE], is run again, up to [DECOMPRESS_ATTEMPTS] runs.
     *
     * @throws IllegalStateException if the archive header holds no plain file name, if the compiler fails, or if it
     * aborts on every run.
     */
    suspend fun extract(
        uzPath: Path,
        outputPath: Path? = null,
    ): Path {
        val fileName = uzPath.fileName.toString().replace(UZ_SUFFIX, "")
        val targetPath = outputPath ?: uzPath.toAbsolutePath().parent[fileName]

        val resultPath = directory[archivedName(uzPath)]
        lock.withLock {
            decompress(uzPath)
            withContext(Dispatchers.IO) { check(resultPath.exists()) { "UCC failed to extract $uzPath" } }
            if (resultPath != targetPath) {
                resultPath.move(targetPath)
            }
        }
        return targetPath
    }

    /**
     * Runs the compiler decompressing [uzPath], running it again while it aborts, up to [DECOMPRESS_ATTEMPTS] runs.
     *
     * @throws IllegalStateException if the compiler fails, or aborts on every run.
     */
    private suspend fun decompress(uzPath: Path) {
        val result =
            (2..DECOMPRESS_ATTEMPTS).fold(run("decompress", uzPath.absolutePathString())) { previous, _ ->
                when (previous.exitCode) {
                    ABORT_EXIT_CODE -> run("decompress", uzPath.absolutePathString())
                    else -> previous
                }
            }
        result.orThrow()
    }

    /**
     * Returns the file name held in the header of the `.uz` archive at [uzPath]. The compiler extracts the archive
     * under this name.
     *
     * The header holds a signature, then the file name as a string. The string is a compact index giving its length,
     * then that many bytes ending in a null.
     *
     * @throws IllegalStateException if the header holds no file name, holds a UTF-16 file name, or holds a name that
     * is not a plain file name, such as one with a path separator, a drive or a `..`.
     */
    private suspend fun archivedName(uzPath: Path): String {
        val header =
            withContext(Dispatchers.IO) {
                uzPath.inputStream().use { it.readNBytes(UZ_HEADER_BYTES) }
            }
        val length = UtCompactIndex.read(header, Int.SIZE_BYTES)
        checkNotNull(length) { "UT archive header of $uzPath ends before its file name" }
        check(length.value >= 0) { "UT archive header of $uzPath holds a UTF-16 file name" }
        val start = Int.SIZE_BYTES + length.size
        check(length.value in 1..header.size - start) { "UT archive header of $uzPath holds no file name" }
        val name = String(header, start, length.value - 1, UtPlatform.CHARSET)
        check(name.isPlainFileName()) { "UT archive header of $uzPath holds $name, which is not a plain file name" }
        return name
    }

    /**
     * Compiles the UnrealScript package rooted at [sourcesPath], copies the package to [outputPath] and returns
     * [outputPath].
     *
     * The name of [sourcesPath] is the package name. The directory holds the `Classes` directory the compiler reads
     * the sources from. The configuration is the default configuration of the installation with [configure] applied
     * on top of it and the `EditPackages` entry of the compiled package appended last.
     *
     * @throws IllegalStateException if [sourcesPath] is not a directory, or if the compiler fails.
     */
    suspend fun make(
        sourcesPath: Path,
        outputPath: Path,
        configure: UtIni.() -> Unit = {},
    ): Path {
        withContext(Dispatchers.IO) { check(sourcesPath.isDirectory()) { "UT package sources not found at $sourcesPath" } }

        val packageName = sourcesPath.fileName.toString()
        val packageFileName = "$packageName.u"
        val producedPath = directory[packageFileName]

        return workspace("ucc", config = config.directoriesConfig) { workspace ->
            val iniPath =
                defaultConfig()
                    .apply(configure)
                    .apply { add(EDITOR_SECTION, EDIT_PACKAGES, packageName) }
                    .write(workspace["make.ini"])

            val makeLogPath = workspace["make.log"]
            val packagePath = workspace[packageFileName]
            lock.withLock {
                val result = compile(sourcesPath, producedPath, iniPath)
                withContext(Dispatchers.IO) { makeLogPath.writeText(result.output.joinToString(System.lineSeparator())) }
                result.orThrow()

                withContext(Dispatchers.IO) {
                    check(producedPath.exists()) { "UCC failed to compile $packageName, see $makeLogPath" }
                }
                producedPath.move(packagePath)
            }
            packagePath.copy(outputPath, overwrite = true)
        }
    }

    /**
     * Returns the default configuration of the installation.
     *
     * @throws IllegalStateException if the installation holds no default configuration.
     */
    private suspend fun defaultConfig(): UtIni {
        val defaultPath = installation.defaultIniPath
        withContext(Dispatchers.IO) { check(defaultPath.exists()) { "UT default configuration not found at $defaultPath" } }
        return UtIni.read(defaultPath)
    }

    /**
     * Returns the result of compiling the package at [sourcesPath] with the configuration at [iniPath], without
     * inspecting its exit code.
     *
     * The sources are staged in the installation directory for the run and removed after it. The package an earlier
     * run left at [producedPath] is deleted first.
     */
    @OptIn(ExperimentalPathApi::class)
    private suspend fun compile(
        sourcesPath: Path,
        producedPath: Path,
        iniPath: Path,
    ): ExecutableResult {
        val packageName = sourcesPath.fileName.toString()
        val stagedPath = installation.directory[packageName]
        return try {
            withContext(Dispatchers.IO) {
                stagedPath.deleteRecursively()
                sourcesPath.copyToRecursively(stagedPath, followLinks = false, overwrite = true)
                producedPath.deleteIfExists()
            }
            run("make", "-nobind", "-bytehax", "-packages=$packageName", "-ini=${iniPath.absolutePathString()}")
        } finally {
            withContext(NonCancellable) { stagedPath.deleteCatching() }
        }
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
         * The exit code of a compiler aborted by `SIGABRT`, as a failed assertion aborts it.
         */
        private const val ABORT_EXIT_CODE = 134

        /**
         * The number of runs a decompression gets while the compiler aborts.
         */
        private const val DECOMPRESS_ATTEMPTS = 3

        private const val UZ_HEADER_BYTES = 1024
        private val UZ_SUFFIX = Regex("""\.uz$""", RegexOption.IGNORE_CASE)

        /**
         * The lock of each `System` directory a compiler has run in.
         */
        private val LOCKS = ConcurrentHashMap<Path, Mutex>()
    }
}
