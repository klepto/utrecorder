package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.Workspace
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.days

class UtInstallationTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utinstallation-test")

        afterSpec { root.toFile().deleteRecursively() }

        fun installation(name: String): UtInstallation {
            val directory = root / name
            val executable = directory / UtDirectory.System.name / UtPlatform.current().gameExecutable
            executable.createParentDirectories()
            executable.writeText("")
            return UtInstallation(directory, Workspace(root / "tasks", keep = false, retention = 7.days))
        }

        fun emptyFile(path: Path): Path = path.createParentDirectories().also { it.writeText("") }

        test("defaultIniPath is Default.ini in the system directory") {
            val installation = installation("default-ini")

            installation.defaultIniPath shouldBe root / "default-ini" / "System" / "Default.ini"
        }

        test("packages lists the packages of the system directory, matching the extension ignoring case") {
            val installation = installation("system-packages")
            emptyFile(installation.systemDirectory / "Engine.u")
            emptyFile(installation.systemDirectory / "BTPog.U")

            installation.packages(UtDirectory.System).map { it.name }.toSet() shouldBe setOf("Engine", "BTPog")
        }

        test("packages does not list the files of another extension in that directory") {
            val installation = installation("other-extensions")
            emptyFile(installation.systemDirectory / "Engine.u")
            (installation.systemDirectory / "UnrealTournament.ini").writeText("[Engine.Engine]")
            (installation.systemDirectory / "CTF-BT-II.unr").writeText("a map does not live in System")

            installation.packages(UtDirectory.System).map { it.name } shouldBe listOf("Engine")
        }

        test("packages does not list subdirectories, nor a package one level down") {
            val installation = installation("subdirectories")
            emptyFile(installation.systemDirectory / "Engine.u")
            (installation.systemDirectory / "Cache.u").createDirectories()
            emptyFile(installation.systemDirectory / "Cache.u" / "Nested.u")

            installation.packages(UtDirectory.System).map { it.name } shouldBe listOf("Engine")
        }

        test("packages of a directory that is not there gives an empty list") {
            val installation = installation("no-maps-directory")

            installation.packages(UtDirectory.Maps) shouldBe emptyList()
        }

        test("packages of a directory that declares no extension gives an empty list") {
            val installation = installation("system400")
            emptyFile(installation.directory / UtDirectory.System400.name / "Engine.u")

            installation.packages(UtDirectory.System400) shouldBe emptyList()
        }

        test("each listed package carries the path of its file") {
            val installation = installation("package-paths")
            val path = emptyFile(installation.systemDirectory / "Engine.u")

            installation.packages(UtDirectory.System).single().path shouldBe path
        }
    })
