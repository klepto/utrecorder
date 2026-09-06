package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.Workspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Tests for [UtCompiler.make] against a fake UCC executable, since a real compile is out of scope for a unit test.
 */
class UtCompilerTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utcompiler-test")
        val sourcesRoot = root / "sources"
        val tasksDirectory = root / "tasks"

        afterSpec { root.toFile().deleteRecursively() }

        /**
         * Writes a fake UCC executable under `<installRoot>/System` that emits some output, and either touches
         * `<package>.u` next to itself and exits successfully, or exits with a failure code without producing anything.
         */
        fun createFakeInstall(
            name: String,
            succeed: Boolean,
        ): Path {
            val systemDirectory = root / name / "System"
            systemDirectory.createDirectories()
            (systemDirectory / "Default.ini").writeText(
                "[Editor.EditorEngine]\nEditPackages=Core",
            )

            val windows = UT_PLATFORM == UtPlatform.WINDOWS
            val scriptPath = systemDirectory / if (windows) "UCC.bat" else "ucc-bin"
            val script =
                if (windows) {
                    if (succeed) {
                        """
                        @echo off
                        echo FAKE UCC: compiling
                        set PKG=%~5
                        type nul > "%~dp0%PKG%.u"
                        exit /b 0
                        """.trimIndent()
                    } else {
                        """
                        @echo off
                        echo FAKE UCC: simulated failure
                        exit /b 3
                        """.trimIndent()
                    }
                } else {
                    if (succeed) {
                        """
                        #!/bin/sh
                        echo "FAKE UCC: compiling"
                        PKGARG=${'$'}4
                        PKG=${'$'}{PKGARG#-packages=}
                        DIR=$(cd "$(dirname "${'$'}0")" && pwd)
                        touch "${'$'}DIR/${'$'}PKG.u"
                        exit 0
                        """.trimIndent()
                    } else {
                        """
                        #!/bin/sh
                        echo "FAKE UCC: simulated failure"
                        exit 3
                        """.trimIndent()
                    }
                }
            scriptPath.writeText(script)
            if (!windows) scriptPath.toFile().setExecutable(true)
            return scriptPath
        }

        val okScript = createFakeInstall("ok-install", succeed = true)
        val failScript = createFakeInstall("fail-install", succeed = false)

        context("make") {
            test("creates a task directory holding make.ini and make.log") {
                val compiler = UtCompiler(okScript, Workspace(directory = tasksDirectory, keep = true))
                val sourcesPath = createSources(sourcesRoot, "PackageOne")
                val before = taskDirectories(tasksDirectory)

                compiler.make(sourcesPath, root / "out" / "PackageOne.u")

                val taskDirectory = (taskDirectories(tasksDirectory) - before).single()
                (taskDirectory / "make.ini").exists() shouldBe true
                (taskDirectory / "make.log").exists() shouldBe true
            }

            test("make.ini is Default.ini plus EditPackages, with the configure block applied on top") {
                val compiler = UtCompiler(okScript, Workspace(directory = tasksDirectory, keep = true))
                val sourcesPath = createSources(sourcesRoot, "PackageTwo")
                val before = taskDirectories(tasksDirectory)

                compiler.make(sourcesPath, root / "out" / "PackageTwo.u") {
                    this["Editor.EditorEngine", "UseSound"] = "False"
                }

                val taskDirectory = (taskDirectories(tasksDirectory) - before).single()
                val ini = UtIni.read(taskDirectory / "make.ini")

                ini["Editor.EditorEngine", "EditPackages"] shouldBe listOf("Core", "PackageTwo")
                ini["Editor.EditorEngine", "UseSound"] shouldBe listOf("False")
            }

            test("copies the compiled package to outputPath and keeps a copy in the task directory") {
                val compiler = UtCompiler(okScript, Workspace(directory = tasksDirectory, keep = true))
                val sourcesPath = createSources(sourcesRoot, "PackageThree")
                val outputPath = root / "out" / "PackageThree.u"
                val before = taskDirectories(tasksDirectory)

                val result = compiler.make(sourcesPath, outputPath)

                val taskDirectory = (taskDirectories(tasksDirectory) - before).single()
                result shouldBe outputPath
                outputPath.exists() shouldBe true
                (taskDirectory / "PackageThree.u").exists() shouldBe true
            }

            test("removes the staged package sources from the installation root after a successful run") {
                val compiler = UtCompiler(okScript, Workspace(directory = tasksDirectory))
                val sourcesPath = createSources(sourcesRoot, "PackageFour")

                compiler.make(sourcesPath, root / "out" / "PackageFour.u")

                (okScript.parent.parent / "PackageFour").exists() shouldBe false
            }

            test("leaves no task directory behind after a successful run when keep is off") {
                val compiler = UtCompiler(okScript, Workspace(directory = tasksDirectory))
                val sourcesPath = createSources(sourcesRoot, "PackageSix")
                val before = taskDirectories(tasksDirectory)

                compiler.make(sourcesPath, root / "out" / "PackageSix.u")

                (taskDirectories(tasksDirectory) - before) shouldBe emptySet()
            }

            test("keeps the task directory of a failed run with make.log written, and throws") {
                val compiler = UtCompiler(failScript, Workspace(directory = tasksDirectory))
                val sourcesPath = createSources(sourcesRoot, "PackageFive")
                val before = taskDirectories(tasksDirectory)

                shouldThrow<IllegalStateException> {
                    compiler.make(sourcesPath, root / "out" / "PackageFive.u")
                }

                val taskDirectory = (taskDirectories(tasksDirectory) - before).single()
                (taskDirectory / "make.log").exists() shouldBe true
                (taskDirectory / "PackageFive.u").exists() shouldBe false
                (failScript.parent.parent / "PackageFive").exists() shouldBe false
            }
        }
    })
