package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readText

/**
 * Tests [UtCompiler.make] against a real Unreal Tournament installation and a real compiler, pinning the assumptions
 * the fake compiler in [UtCompilerTest] cannot reach: that UCC accepts an absolute `-ini=` path, that it resolves a
 * package staged next to `System`, and that it writes the compiled package into `System`.
 *
 * Opt-in. The installation is downloaded on the first run, so this spec is excluded from `test` and only runs under
 * `./gradlew :utrecorder-ut99:integrationTest`.
 *
 * @author Augustinas R. <http://github.com/klepto>
 */
class UtCompilerIntegrationTest :
    FunSpec({
        val sourcesRoot = createTempDirectory("utrecorder-utcompiler-integration")
        val config = UtConfig.DEFAULT
        val tasksDirectory = sourcesRoot / "tasks"

        beforeSpec { UtInstaller(config).install() }
        afterSpec { sourcesRoot.toFile().deleteRecursively() }

        val executablePath by lazy { config.gameDirectory[UtDirectory.System][UT_PLATFORM.compilerExecutable] }

        fun compiler(keep: Boolean = false) = UtCompiler(executablePath, Workspace(directory = tasksDirectory, keep = keep))

        context("make") {
            test("compiles a package into a real Unreal Tournament package file") {
                val packageName = "UtRecorderProbe"
                val sourcesPath = createSources(sourcesRoot, packageName, "class $packageName extends Object;")
                val outputPath = sourcesRoot / "out" / "$packageName.u"
                val before = taskDirectories(tasksDirectory)

                val result = compiler(keep = true).make(sourcesPath, outputPath)

                result shouldBe outputPath
                outputPath.exists() shouldBe true
                (outputPath.fileSize() > 0) shouldBe true

                val taskDirectory = (taskDirectories(tasksDirectory) - before).single()
                (taskDirectory / "make.ini").exists() shouldBe true
                (taskDirectory / "make.log").exists() shouldBe true
                (taskDirectory / "$packageName.u").exists() shouldBe true

                (config.gameDirectory[UtDirectory.System] / "$packageName.u").exists() shouldBe false
                (config.gameDirectory / packageName).exists() shouldBe false
            }

            test("applies the configure block on top of the installation defaults") {
                val packageName = "UtRecorderConfigured"
                val sourcesPath = createSources(sourcesRoot, packageName, "class $packageName extends Object;")
                val outputPath = sourcesRoot / "out" / "$packageName.u"
                val before = taskDirectories(tasksDirectory)

                compiler(keep = true).make(sourcesPath, outputPath) {
                    this["Core.System", "Suppress"] = "DevCompile"
                }

                val ini = UtIni.read((taskDirectories(tasksDirectory) - before).single() / "make.ini")
                ini["Editor.EditorEngine", "EditPackages"].last() shouldBe packageName
                ini["Core.System", "Suppress"] shouldBe listOf("DevCompile")
            }

            test("leaves no task directory behind after a successful run when keep is off") {
                val packageName = "UtRecorderClean"
                val sourcesPath = createSources(sourcesRoot, packageName, "class $packageName extends Object;")
                val outputPath = sourcesRoot / "out" / "$packageName.u"
                val before = taskDirectories(tasksDirectory)

                compiler().make(sourcesPath, outputPath)

                (taskDirectories(tasksDirectory) - before) shouldBe emptySet()
            }

            test("fails the run and keeps the compiler diagnostics when the sources don't compile") {
                val packageName = "UtRecorderBroken"
                val sourcesPath = createSources(sourcesRoot, packageName, "class $packageName extends NoSuchParentClass;")
                val outputPath = sourcesRoot / "out" / "$packageName.u"
                val before = taskDirectories(tasksDirectory)

                shouldThrow<IllegalStateException> { compiler().make(sourcesPath, outputPath) }

                outputPath.exists() shouldBe false
                (config.gameDirectory / packageName).exists() shouldBe false

                val taskDirectory = (taskDirectories(tasksDirectory) - before).single()
                (taskDirectory / "make.log").readText() shouldContain packageName
                (taskDirectory / "$packageName.u").exists() shouldBe false
            }
        }
    })
