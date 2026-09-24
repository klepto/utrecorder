package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.ut99.UtDirectory.Maps
import dev.klepto.utrecorder.ut99.UtDirectory.System
import dev.klepto.utrecorder.ut99.UtDirectory.System400
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeText

class UtInstallationTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utinstallation-test")

        afterSpec { root.toFile().deleteRecursively() }

        fun emptyFile(path: Path): Path = path.createParentDirectories().also { it.writeText("") }

        fun installation(name: String): UtInstallation {
            val directory = root / name
            emptyFile(directory / System.name / UtPlatform.current().gameExecutable)
            return UtInstallation(directory, UtConfig(ConfigReader.EMPTY, DirectoriesConfig(ConfigReader.EMPTY, home = root / "home")))
        }

        test("defaultIniPath is Default.ini in the system directory") {
            val installation = installation("default-ini")

            installation.defaultIniPath shouldBe root / "default-ini" / "System" / "Default.ini"
        }

        test("packages returns the packages the system directory holds, matching the extension ignoring case") {
            val installation = installation("system-packages")
            emptyFile(installation.systemDirectory / "Engine.u")
            emptyFile(installation.systemDirectory / "BTPog.U")

            installation.packages(System).map { it.name }.toSet() shouldBe setOf("Engine", "BTPog")
        }

        test("packages does not return the files of another extension in the directory") {
            val installation = installation("other-extensions")
            emptyFile(installation.systemDirectory / "Engine.u")
            (installation.systemDirectory / "UnrealTournament.ini").writeText("[Engine.Engine]")
            (installation.systemDirectory / "CTF-BT-II.unr").writeText("a map does not live in System")

            installation.packages(System).map { it.name } shouldBe listOf("Engine")
        }

        test("packages does not return subdirectories, nor a package one level down") {
            val installation = installation("subdirectories")
            emptyFile(installation.systemDirectory / "Engine.u")
            (installation.systemDirectory / "Cache.u").createDirectories()
            emptyFile(installation.systemDirectory / "Cache.u" / "Nested.u")

            installation.packages(System).map { it.name } shouldBe listOf("Engine")
        }

        test("packages returns an empty list when the directory does not exist") {
            val installation = installation("no-maps-directory")

            installation.packages(Maps) shouldBe emptyList()
        }

        test("packages returns an empty list when the directory declares no extension") {
            val installation = installation("system400")
            emptyFile(installation.directory / System400.name / "Engine.u")

            installation.packages(System400) shouldBe emptyList()
        }

        test("packages returns each package with the path of its file") {
            val installation = installation("package-paths")
            val path = emptyFile(installation.systemDirectory / "Engine.u")

            installation.packages(System).single().path shouldBe path
        }
    })
