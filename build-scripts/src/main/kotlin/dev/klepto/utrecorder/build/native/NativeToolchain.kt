package dev.klepto.utrecorder.build.native

import dev.klepto.utrecorder.build.native.NativeToolchain.Kind.GCC
import dev.klepto.utrecorder.build.native.NativeToolchain.Kind.MINGW
import dev.klepto.utrecorder.build.native.NativeToolchain.Kind.MSVC
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Serializable
import javax.inject.Inject

/**
 * C toolchain that compiles native binaries on the machine the build runs on.
 *
 * Use [Source] to discover the toolchain of that machine.
 *
 * @property kind Compiler family, which decides the shape of the command line.
 * @property command Compiler to invoke. For [MSVC], the environment script that puts the compiler on the path instead.
 */
data class NativeToolchain(
    val kind: Kind,
    val command: String,
) : Serializable {
    /**
     * Compiler family of a [NativeToolchain].
     */
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
         * Visual Studio 32-bit toolset, reached through `VsDevCmd.bat`.
         */
        MSVC,
    }

    /**
     * Discovery of the C toolchain for a platform. Obtains nothing when the machine holds no toolchain.
     *
     * On Windows, MinGW on the path comes first and the Visual Studio environment script second.
     */
    abstract class Source : ValueSource<NativeToolchain, Source.Parameters> {
        /**
         * Parameters of the toolchain discovery.
         */
        interface Parameters : ValueSourceParameters {
            /**
             * Name of the [NativePlatform] to find a toolchain for.
             */
            val platform: Property<String>
        }

        /**
         * Service that runs the probing commands.
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
                runsCatching(GCC_COMMAND).getOrDefault(false) -> NativeToolchain(GCC, GCC_COMMAND)
                else -> null
            }

        private fun mingw(platform: NativePlatform): NativeToolchain? =
            when {
                !platform.isWindows -> null
                runsCatching(MINGW_COMMAND).getOrDefault(false) -> NativeToolchain(MINGW, MINGW_COMMAND)
                else -> null
            }

        private fun msvc(platform: NativePlatform): NativeToolchain? =
            when {
                !platform.isWindows -> null
                else -> environmentScriptCatching().getOrNull()?.let { NativeToolchain(MSVC, it.absolutePath) }
            }

        /**
         * Returns whether [tool] answers `--version` with exit code `0`, or the failure that kept it from running.
         */
        private fun runsCatching(tool: String): Result<Boolean> = runCatching { execute(listOf(tool, "--version")) == 0 }

        /**
         * Returns the Visual Studio environment script, or the failure that kept `vswhere` from running. If no
         * installation holds the script, returns `null` instead.
         */
        private fun environmentScriptCatching(): Result<File?> =
            runCatching {
                val vswhere = vswhere() ?: return@runCatching null
                val output = ByteArrayOutputStream()
                val arguments = listOf(vswhere.absolutePath, "-latest", "-products", "*", "-property", "installationPath")
                if (execute(arguments, output) != 0) return@runCatching null

                val installation = firstLine(output) ?: return@runCatching null
                File(installation, "Common7/Tools/VsDevCmd.bat").takeIf { it.exists() }
            }

        /**
         * Runs [command] and returns its exit code. Everything the command prints is written into [output]. If
         * [output] is not provided, the printed text is discarded.
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

        companion object {
            private const val GCC_COMMAND = "gcc"
            private const val MINGW_COMMAND = "i686-w64-mingw32-gcc"

            /**
             * Returns `vswhere.exe`, the Visual Studio locator. If it is not installed, returns `null` instead.
             */
            private fun vswhere(): File? =
                System
                    .getenv("ProgramFiles(x86)")
                    ?.let { File(it, "Microsoft Visual Studio/Installer/vswhere.exe") }
                    ?.takeIf { it.exists() }

            /**
             * Returns the first non-blank line of [output], trimmed. If [output] holds none, returns `null` instead.
             */
            private fun firstLine(output: ByteArrayOutputStream): String? =
                output
                    .toByteArray()
                    .decodeToString()
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
        }
    }
}
