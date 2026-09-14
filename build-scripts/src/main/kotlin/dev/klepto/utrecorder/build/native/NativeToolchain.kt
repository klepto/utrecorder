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
 * The C toolchain a native binary is compiled by, carried as a task input.
 *
 * Use [Source] to discover the toolchain of the machine the build runs on.
 *
 * @property kind The compiler family, which decides the shape of the command line.
 * @property command The compiler to invoke, or the environment script that puts it on the path for [Kind.MSVC].
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
     * Discovery of the C toolchain for a platform, obtaining nothing when the machine holds none. On Windows MinGW on the
     * path comes first and the Visual Studio environment script second.
     */
    abstract class Source : ValueSource<NativeToolchain, Source.Parameters> {
        /**
         * The parameters the discovery reads.
         */
        interface Parameters : ValueSourceParameters {
            /**
             * The name of the [NativePlatform] to find a toolchain for.
             */
            val platform: Property<String>
        }

        /**
         * The service the discovery runs its probing commands through.
         */
        @get:Inject
        abstract val exec: ExecOperations

        override fun obtain(): NativeToolchain? {
            val platform = NativePlatform.valueOf(parameters.platform.get())
            return gcc(platform) ?: mingw(platform) ?: msvc(platform)
        }

        private fun gcc(platform: NativePlatform): NativeToolchain? =
            when {
                platform.isWindows -> null
                runsCatching(GCC).getOrDefault(false) -> NativeToolchain(Kind.GCC, GCC)
                else -> null
            }

        private fun mingw(platform: NativePlatform): NativeToolchain? =
            when {
                !platform.isWindows -> null
                runsCatching(MINGW).getOrDefault(false) -> NativeToolchain(Kind.MINGW, MINGW)
                else -> null
            }

        private fun msvc(platform: NativePlatform): NativeToolchain? =
            when {
                !platform.isWindows -> null
                else -> visualStudioToolsCatching().getOrNull()?.let { NativeToolchain(Kind.MSVC, it.absolutePath) }
            }

        /**
         * Returns whether the given [tool] answers `--version` with exit code `0`, or the failure that kept it from
         * running.
         */
        private fun runsCatching(tool: String): Result<Boolean> = runCatching { execute(listOf(tool, "--version")) == 0 }

        /**
         * Returns the Visual Studio environment script, `null` when no installation holds one, or the failure that kept
         * `vswhere` from running.
         */
        private fun visualStudioToolsCatching(): Result<File?> =
            runCatching {
                val vswhere = vswhere() ?: return@runCatching null
                val output = ByteArrayOutputStream()
                val arguments = listOf(vswhere.absolutePath, "-latest", "-products", "*", "-property", "installationPath")
                if (execute(arguments, output) != 0) return@runCatching null

                val installation = firstLine(output) ?: return@runCatching null
                File(installation, "Common7/Tools/VsDevCmd.bat").takeIf { it.exists() }
            }

        /**
         * Returns the Visual Studio locator, or `null` when it is not installed.
         */
        private fun vswhere(): File? =
            System
                .getenv("ProgramFiles(x86)")
                ?.let { File(it, "Microsoft Visual Studio/Installer/vswhere.exe") }
                ?.takeIf { it.exists() }

        /**
         * Returns the first non-blank line of [output], trimmed, or `null` when it holds none.
         */
        private fun firstLine(output: ByteArrayOutputStream): String? =
            output
                .toByteArray()
                .decodeToString()
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }

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
