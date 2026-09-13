package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.Workspace
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeText

/**
 * Tests for the packages [UtInstallation] reports its directories hold.
 *
 * The installation is a fake tree: a `System` directory holding the package files a test writes, and a placeholder
 * game executable, an empty regular file, only because [UtInstallation]'s init block checks that the path exists. It
 * is never executed.
 */
class UtInstallationTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utinstallation-test")

        afterSpec { root.toFile().deleteRecursively() }

        fun installation(name: String): UtInstallation {
            val directory = root / name
            val executable = directory / UtDirectory.System.name / UT_PLATFORM.gameExecutable
            executable.createParentDirectories()
            executable.writeText("")
            return UtInstallation(directory, Workspace(directory = root / "tasks"))
        }

        test("packages lists the packages of the system directory, matching the extension ignoring case") {
            val installation = installation("system-packages")
            writePackage(installation.systemDirectory / "Engine.u")
            writePackage(installation.systemDirectory / "BTPog.U")

            installation.packages(UtDirectory.System).map { it.name }.toSet() shouldBe setOf("Engine", "BTPog")
        }

        test("packages does not list the files of another extension in that directory") {
            val installation = installation("other-extensions")
            writePackage(installation.systemDirectory / "Engine.u")
            (installation.systemDirectory / "UnrealTournament.ini").writeText("[Engine.Engine]")
            (installation.systemDirectory / "CTF-BT-II.unr").writeText("a map does not live in System")

            installation.packages(UtDirectory.System).map { it.name } shouldBe listOf("Engine")
        }

        test("packages does not list subdirectories, nor a package one level down") {
            val installation = installation("subdirectories")
            writePackage(installation.systemDirectory / "Engine.u")
            (installation.systemDirectory / "Cache.u").createDirectories()
            writePackage(installation.systemDirectory / "Cache.u" / "Nested.u")

            installation.packages(UtDirectory.System).map { it.name } shouldBe listOf("Engine")
        }

        test("packages of a directory that is not there gives an empty list") {
            val installation = installation("no-maps-directory")

            installation.packages(UtDirectory.Maps) shouldBe emptyList()
        }

        test("packages of a directory that declares no extension gives an empty list") {
            val installation = installation("system400")
            writePackage(installation.directory / UtDirectory.System400.name / "Engine.u")

            installation.packages(UtDirectory.System400) shouldBe emptyList()
        }

        test("listing a directory reads no package header") {
            val installation = installation("lazy-guid")
            val path = writePackage(installation.systemDirectory / "Engine.u")

            val listed = installation.packages(UtDirectory.System).single()

            writePackage(path, OTHER_GUID_HEADER_BYTES)
            listed.guid shouldBe OTHER_GUID
        }

        test("each listed package carries the path of its file") {
            val installation = installation("package-paths")
            val path = writePackage(installation.systemDirectory / "Engine.u")

            installation.packages(UtDirectory.System).single().path shouldBe path
        }
    })
