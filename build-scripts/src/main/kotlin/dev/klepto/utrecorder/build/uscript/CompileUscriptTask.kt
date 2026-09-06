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
 * Compiles one UnrealScript package into a `.u` file by forking `UtCli` against the runtime classpath of
 * `utrecorder-ut99`.
 *
 * It forks rather than calls because the build that runs this plugin is an included build: it cannot depend on a
 * subproject of the build it configures, so the only way to reach the compiler is as a resolved artifact.
 *
 * The Unreal Tournament installation UCC comes from is not an input. It is hundreds of megabytes of materialized game
 * data, hashing it would cost more than the compile, and it is fully determined by [compilerClasspath] - the jar it is
 * provisioned by - together with [patchVersion], which overrides the version that jar defaults to.
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
     * The directory the configuration and the output of the compiler run are written to.
     */
    @get:OutputDirectory
    abstract val logDirectory: DirectoryProperty

    /**
     * Holds the compile to one at a time. See [UccService].
     */
    @get:ServiceReference(UCC_SERVICE)
    abstract val ucc: Property<UccService>

    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun compile() {
        val outputPath = outputDirectory.file("${packageName.get()}.u").get().asFile
        val logPath = logDirectory.get().asFile

        // The compiler keeps the job of a failed run, so the previous one has to go or its log outlives its output.
        files.delete {
            delete(outputDirectory, logDirectory)
        }

        exec.javaexec {
            executable = javaLauncher.get().executablePath.asFile.absolutePath
            classpath = compilerClasspath
            mainClass.set(MAIN_CLASS)

            environment("UTRECORDER_HOME", homeDirectory.get().asFile.absolutePath)
            patchVersion.orNull?.let { environment("UTRECORDER_PATCH_VERSION", it) }

            args =
                listOfNotNull(
                    "make",
                    "--sources=${sources.get().asFile.absolutePath}",
                    "--output=${outputPath.absolutePath}",
                    "--jobs=${logPath.absolutePath}",
                    editPackages.get().takeIf { it.isNotEmpty() }?.let { "--edit-packages=${it.joinToString(",")}" },
                )
        }
    }

    companion object {
        /**
         * The entry point of `utrecorder-ut99`. Referenced by name, so the class has to keep it - see `UtCli`.
         */
        private const val MAIN_CLASS = "dev.klepto.utrecorder.ut99.UtCli"
    }
}
