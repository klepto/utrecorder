package dev.klepto.utrecorder.build.native

import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Serializable
import javax.inject.Inject

/**
 * The C toolchain a native binary is compiled by.
 *
 * A task input, so that a build cached against one toolchain is not reused against another - the two do not produce
 * the same binary.
 *
 * @param kind Which compiler this is, which decides the shape of the command line.
 * @param command The compiler to invoke, or the environment script that puts it on the path for [Kind.MSVC].
 */
data class NativeToolchain(
    val kind: Kind,
    val command: String,
) : Serializable {
    enum class Kind {
        /**
         * `gcc`, targeting the machine it runs on.
         */
        GCC,

        /**
         * `i686-w64-mingw32-gcc`, targeting 32-bit Windows.
         */
        MINGW,

        /**
         * The Visual Studio 32-bit toolset, reached through `VsDevCmd.bat`.
         */
        MSVC,
    }

    companion object {
        private const val GCC = "gcc"
        private const val MINGW = "i686-w64-mingw32-gcc"
    }

    /**
     * Discovers the C toolchain for a platform, or nothing when the machine holds none. A task that needs one then
     * fails with its own message, naming what to install for the platform it was asked to build.
     *
     * A [ValueSource] rather than a plain function, because discovery runs external processes and its result is a task
     * input: this way the configuration cache records it properly instead of treating it as an undeclared read.
     *
     * MinGW is preferred on Windows when it is on the path, because it needs no environment script and produces the
     * same 32-bit output. Visual Studio is the fallback, through `VsDevCmd.bat -arch=x86 -host_arch=x64` - the
     * supported cross toolset, since Microsoft deprecated the x86-hosted compiler but never the x86 target.
     */
    abstract class Source : ValueSource<NativeToolchain, Source.Parameters> {
        interface Parameters : ValueSourceParameters {
            /**
             * The name of the [NativePlatform] to find a toolchain for.
             */
            val platform: Property<String>
        }

        @get:Inject
        abstract val exec: ExecOperations

        override fun obtain(): NativeToolchain? {
            val platform = NativePlatform.valueOf(parameters.platform.get())
            if (!platform.isWindows) {
                return if (runs(GCC)) NativeToolchain(Kind.GCC, GCC) else null
            }
            if (runs(MINGW)) return NativeToolchain(Kind.MINGW, MINGW)
            val tools = visualStudioTools() ?: return null
            return NativeToolchain(Kind.MSVC, tools.absolutePath)
        }

        /**
         * Returns whether the given [tool] is on the path and answers `--version`.
         */
        private fun runs(tool: String): Boolean =
            runCatching {
                execute(listOf(tool, "--version")) == 0
            }.getOrDefault(false)

        /**
         * Returns the Visual Studio environment script, or `null` if no installation holds one.
         */
        private fun visualStudioTools(): File? {
            val programFiles = System.getenv("ProgramFiles(x86)") ?: return null
            val vswhere = File(programFiles, "Microsoft Visual Studio/Installer/vswhere.exe")
            if (!vswhere.exists()) return null

            val output = ByteArrayOutputStream()
            val arguments = listOf(vswhere.absolutePath, "-latest", "-products", "*", "-property", "installationPath")
            val status = runCatching { execute(arguments, output) }.getOrNull()
            if (status != 0) return null

            val installation =
                output
                    .toByteArray()
                    .decodeToString()
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() } ?: return null

            return File(installation, "Common7/Tools/VsDevCmd.bat").takeIf { it.exists() }
        }

        /**
         * Runs the given [command], writing everything it prints into [output], and returns its exit code.
         */
        private fun execute(
            command: List<String>,
            output: ByteArrayOutputStream = ByteArrayOutputStream(),
        ): Int =
            exec
                .exec {
                    commandLine(command)
                    standardOutput = output
                    errorOutput = output
                    isIgnoreExitValue = true
                }.exitValue
    }
}
