package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.days

/**
 * Tests [UtCompiler.make] against a real Unreal Tournament installation and a real compiler, pinning the assumptions
 * the fake compiler in [UtCompilerTest] cannot reach: that UCC accepts an absolute `-ini=` path, that it resolves a
 * package staged next to `System`, and that it writes the compiled package into `System`.
 *
 * Opt-in. The installation is downloaded on the first run, so this spec is excluded from `test` and only runs under
 * `./gradlew :utrecorder-ut99:integrationTest`.
 */
class UtCompilerIntegrationTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utcompiler-integration")
        val config = UtConfig.DEFAULT

        beforeSpec { UtInstaller(config).install() }
        afterSpec { root.toFile().deleteRecursively() }

        fun compiler(
            packageName: String,
            keep: Boolean = false,
        ): UtCompiler {
            val workspace = Workspace(root / "tasks" / packageName, keep = keep, retention = 7.days)
            return UtInstallation(config.gameDirectory, workspace).compiler
        }

        fun sources(
            packageName: String,
            body: String = "class $packageName extends Object;",
        ): Path {
            val sourcesPath = root / "sources" / packageName
            (sourcesPath / "Classes").createDirectories()
            (sourcesPath / "Classes" / "$packageName.uc").writeText(body)
            return sourcesPath
        }

        fun taskDirectories(packageName: String): List<Path> =
            (root / "tasks" / packageName).takeIf { it.exists() }?.listDirectoryEntries("ucc-*") ?: emptyList()

        context("make") {
            test("compiles a package into a real Unreal Tournament package file") {
                val packageName = "UtRecorderProbe"
                val outputPath = root / "out" / "$packageName.u"

                val result = compiler(packageName, keep = true).make(sources(packageName), outputPath)

                result shouldBe outputPath
                outputPath.exists() shouldBe true
                (outputPath.fileSize() > 0) shouldBe true

                val taskDirectory = taskDirectories(packageName).single()
                (taskDirectory / "make.ini").exists() shouldBe true
                (taskDirectory / "make.log").exists() shouldBe true
                (taskDirectory / "$packageName.u").exists() shouldBe true

                (config.gameDirectory[UtDirectory.System] / "$packageName.u").exists() shouldBe false
                (config.gameDirectory / packageName).exists() shouldBe false
            }

            test("applies the configure block on top of the installation defaults") {
                val packageName = "UtRecorderConfigured"

                compiler(packageName, keep = true).make(sources(packageName), root / "out" / "$packageName.u") {
                    this["Core.System", "Suppress"] = "DevCompile"
                }

                val ini = UtIni.read(taskDirectories(packageName).single() / "make.ini")
                ini["Editor.EditorEngine", "EditPackages"].last() shouldBe packageName
                ini["Core.System", "Suppress"] shouldBe listOf("DevCompile")
            }

            test("leaves no task directory behind after a successful run when keep is off") {
                val packageName = "UtRecorderClean"

                compiler(packageName).make(sources(packageName), root / "out" / "$packageName.u")

                taskDirectories(packageName) shouldBe emptyList()
            }

            test("fails the run and keeps the compiler diagnostics when the sources don't compile") {
                val packageName = "UtRecorderBroken"
                val outputPath = root / "out" / "$packageName.u"
                val sourcesPath = sources(packageName, "class $packageName extends NoSuchParentClass;")

                shouldThrow<IllegalStateException> { compiler(packageName).make(sourcesPath, outputPath) }

                outputPath.exists() shouldBe false
                (config.gameDirectory / packageName).exists() shouldBe false

                val taskDirectory = taskDirectories(packageName).single()
                (taskDirectory / "make.log").readText() shouldContain packageName
                (taskDirectory / "$packageName.u").exists() shouldBe false
            }
        }
    })
