package dev.klepto.utrecorder.build.uscript

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Task that compiles one UnrealScript package from [sources] into a `.u` file in [outputDirectory]. The compiler is
 * `UtCli`, forked from [compilerClasspath].
 */
@CacheableTask
abstract class CompileUscriptTask : DefaultTask() {
    /**
     * The directory holding the `Classes` directory UCC reads the sources from.
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: DirectoryProperty

    /**
     * The name of the package to compile, which is also the name of the file it produces.
     */
    @get:Input
    abstract val packageName: Property<String>

    /**
     * The packages this one compiles against, beyond the ones the installation configures by default.
     */
    @get:Input
    abstract val editPackages: ListProperty<String>

    /**
     * The Unreal Tournament patch version to provision, or absent to use the one the compiler defaults to.
     */
    @get:Input
    @get:Optional
    abstract val patchVersion: Property<String>

    /**
     * The runtime classpath of `utrecorder-ut99`, holding [MAIN_CLASS].
     */
    @get:Classpath
    abstract val compilerClasspath: ConfigurableFileCollection

    /**
     * The JVM the compiler is forked on.
     */
    @get:Nested
    abstract val javaLauncher: Property<JavaLauncher>

    /**
     * The recorder home directory the compiler provisions its installation into.
     */
    @get:Internal
    abstract val homeDirectory: DirectoryProperty

    /**
     * The directory the compiled package is written to.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /**
     * The directory the configuration and the output of the compiler run are written under. A run that succeeds leaves
     * it empty, a run that fails keeps its task directory there.
     */
    @get:OutputDirectory
    abstract val logDirectory: DirectoryProperty

    /**
     * The build service that holds compiles to one at a time.
     */
    @get:ServiceReference(UCC_SERVICE)
    abstract val ucc: Property<UccService>

    /**
     * The service the compiler is forked through.
     */
    @get:Inject
    abstract val exec: ExecOperations

    /**
     * The service the task directories are cleared through.
     */
    @get:Inject
    abstract val files: FileSystemOperations

    /**
     * Compiles the package. Clears [outputDirectory] and [logDirectory] of the previous run first.
     */
    @TaskAction
    fun compile() {
        files.delete {
            delete(outputDirectory, logDirectory)
        }

        exec.javaexec {
            executable = javaLauncher.get().executablePath.asFile.absolutePath
            classpath = compilerClasspath
            mainClass.set(MAIN_CLASS)

            environment("UTRECORDER_HOME", homeDirectory.get().asFile.absolutePath)
            patchVersion.orNull?.let { environment("UTRECORDER_PATCH_VERSION", it) }

            args = compilerArguments()
        }
    }

    /**
     * Returns the `UtCli` arguments that compile this package into [outputDirectory]. The compiler runs under
     * [logDirectory], so a failed compile leaves its configuration and its log there.
     */
    private fun compilerArguments(): List<String> =
        listOfNotNull(
            "make",
            "--sources=${sources.get().asFile.absolutePath}",
            "--output=${outputDirectory.file("${packageName.get()}.u").get().asFile.absolutePath}",
            "--tasks=${logDirectory.get().asFile.absolutePath}",
            editPackagesArgument(),
        )

    /**
     * Returns the `--edit-packages` argument, or `null` when this package compiles against no extra packages.
     */
    private fun editPackagesArgument(): String? =
        editPackages
            .get()
            .takeIf { it.isNotEmpty() }
            ?.let { "--edit-packages=${it.joinToString(",")}" }

    companion object {
        /**
         * The command line entry point of `utrecorder-ut99` the compiler is forked through.
         */
        private const val MAIN_CLASS = "dev.klepto.utrecorder.ut99.UtCli"
    }
}
