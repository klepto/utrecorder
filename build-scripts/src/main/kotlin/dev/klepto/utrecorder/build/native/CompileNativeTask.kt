package dev.klepto.utrecorder.build.native

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Compiles one native binary from the C sources of a unit.
 *
 * The compiler runs in a work directory rather than in the output directory, and only the binary it produced is copied
 * out. Every toolchain leaves intermediates behind - MSVC alone writes `.obj`, `.exp` and `.lib` - and the output
 * directory is what lands in the jar, so it has to hold the artifacts and nothing else.
 *
 * A binary the current platform does not build is not an error. Its output is cleared and the task succeeds, so a
 * Linux build of a module holding a Windows-only injector is a normal build rather than a broken one.
 */
@CacheableTask
abstract class CompileNativeTask : DefaultTask() {
    /**
     * The directory holding the C sources.
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty

    /**
     * The base name of the binary, without the platform extension.
     */
    @get:Input
    abstract val binaryName: Property<String>

    /**
     * What the sources compile to.
     */
    @get:Input
    abstract val binaryType: Property<NativeBinaryType>

    /**
     * The platform the build targets.
     */
    @get:Input
    abstract val platform: Property<NativePlatform>

    /**
     * The platforms this binary is built for. A [platform] outside this set skips the compile.
     */
    @get:Input
    abstract val platforms: SetProperty<NativePlatform>

    /**
     * The toolchain the binary is compiled by, absent when the machine holds none.
     */
    @get:Input
    @get:Optional
    abstract val toolchain: Property<NativeToolchain>

    /**
     * The directory the binary is written to.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /**
     * The directory the compiler output is written to.
     */
    @get:OutputDirectory
    abstract val logDirectory: DirectoryProperty

    /**
     * The directory the compiler runs in, holding the intermediates it leaves behind.
     */
    @get:Internal
    abstract val workDirectory: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun compile() {
        val output = outputDirectory.get().asFile
        val work = workDirectory.get().asFile
        val logs = logDirectory.get().asFile

        files.delete { delete(output, work, logs) }
        output.mkdirs()
        logs.mkdirs()

        val target = platform.get()
        if (target !in platforms.get()) {
            logger.lifecycle("${binaryName.get()} is not built for ${target.id}, skipping")
            return
        }
        work.mkdirs()

        val toolchain = toolchain.orNull ?: error(missingToolchain(target))

        val sourceFiles = sources.asFileTree.matching { include("**/*.c") }.files.sortedBy { it.absolutePath }
        check(sourceFiles.isNotEmpty()) { "No C sources under ${sources.get().asFile}" }

        val fileName = target.fileName(binaryName.get(), binaryType.get())
        val captured = ByteArrayOutputStream()
        val result =
            exec.exec {
                workingDir = work
                commandLine(command(toolchain, sourceFiles, fileName))
                standardOutput = captured
                errorOutput = captured
                isIgnoreExitValue = true
            }

        val log = logs.resolve(COMPILE_LOG)
        log.writeBytes(captured.toByteArray())
        check(result.exitValue == 0) { "Could not compile $fileName, see $log" }

        val produced = work.resolve(fileName)
        check(produced.isFile) { "${toolchain.kind} produced no $fileName, see $log" }
        produced.copyTo(output.resolve(fileName), overwrite = true)
    }

    /**
     * Returns the command line that compiles [sourceFiles] into [fileName] with the given [toolchain]. Every path is
     * absolute except the output, which is relative because the compiler runs in the work directory.
     */
    private fun command(
        toolchain: NativeToolchain,
        sourceFiles: List<File>,
        fileName: String,
    ): List<String> {
        val paths = sourceFiles.map { it.absolutePath }
        val shared = binaryType.get() == NativeBinaryType.SHARED_LIBRARY
        return when (toolchain.kind) {
            // -m32 because the Linux patch is x86: a 64-bit shared object cannot be loaded into a 32-bit game. It
            // needs gcc-multilib and libc6-dev-i386 on a 64-bit host.
            NativeToolchain.Kind.GCC ->
                if (shared) {
                    listOf(toolchain.command, "-m32", "-shared", "-fPIC", "-O2", "-o", fileName) + paths + "-ldl"
                } else {
                    listOf(toolchain.command, "-m32", "-O2", "-o", fileName) + paths
                }

            NativeToolchain.Kind.MINGW ->
                if (shared) {
                    listOf(toolchain.command, "-O2", "-shared", "-o", fileName) +
                        paths + listOf("-lws2_32", "-static-libgcc")
                } else {
                    // -municode gives the injector the wmain entry point it is written against.
                    listOf(toolchain.command, "-O2", "-municode", "-o", fileName) + paths + "-static-libgcc"
                }

            NativeToolchain.Kind.MSVC -> listOf("cmd", "/c", visualStudioCommand(toolchain, paths, fileName, shared))
        }
    }

    /**
     * Returns the shell line that puts the 32-bit Visual Studio toolset on the path and then compiles with it.
     */
    private fun visualStudioCommand(
        toolchain: NativeToolchain,
        paths: List<String>,
        fileName: String,
        shared: Boolean,
    ): String {
        val sources = paths.joinToString(" ") { "\"$it\"" }
        val compile =
            if (shared) {
                "cl /nologo /O2 /LD $sources /Fe:\"$fileName\" /link ws2_32.lib gdi32.lib user32.lib"
            } else {
                "cl /nologo /O2 $sources /Fe:\"$fileName\" /link /SUBSYSTEM:CONSOLE /ENTRY:wmainCRTStartup"
            }
        return "call \"${toolchain.command}\" -arch=x86 -host_arch=x64 -no_logo && $compile"
    }

    /**
     * Returns the message naming what to install when no toolchain was found for [target].
     */
    private fun missingToolchain(target: NativePlatform): String =
        if (target.isWindows) {
            "No 32-bit C toolchain found. Install Visual Studio Build Tools with the x86 compiler, " +
                "or put i686-w64-mingw32-gcc on PATH, to build ${binaryName.get()}."
        } else {
            "gcc is not on PATH. Install it to build ${binaryName.get()}."
        }

    companion object {
        /**
         * The name the compiler output of a run is written under.
         */
        private const val COMPILE_LOG = "compile.log"
    }
}
