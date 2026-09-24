package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.io.Executable
import dev.klepto.utrecorder.common.io.startAndAwait
import dev.klepto.utrecorder.ut99.UtDirectory.Maps
import dev.klepto.utrecorder.ut99.UtPlatform.WINDOWS
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * Tests [UtMapInstaller] against a fake UCC in a stub installation.
 *
 * The fake UCC is a shell script on Linux and a C# program built with the .NET Framework compiler on Windows. It writes
 * the file named by the header of a `.uz` archive, holding the bytes that follow the header.
 */
class UtMapInstallerTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utmapinstaller-test")
        val platform = UtPlatform.current()

        val fakeCompilerScript =
            """
            #!/bin/sh
            DIRECTORY=${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)
            LENGTH=${'$'}(od -An -tu1 -j4 -N1 "${'$'}2" | tr -d ' ')
            NAME=${'$'}(dd if="${'$'}2" bs=1 skip=5 count=${'$'}((LENGTH - 1)) 2>/dev/null)
            tail -c +${'$'}((LENGTH + 6)) "${'$'}2" > "${'$'}DIRECTORY/${'$'}NAME"
            echo "FAKE UCC: decompressed ${'$'}NAME"
            exit 0
            """.trimIndent()

        val fakeCompilerSource =
            """
            using System;
            using System.IO;

            class FakeUcc
            {
                static int Main(string[] arguments)
                {
                    string directory = AppDomain.CurrentDomain.BaseDirectory;
                    byte[] archive = File.ReadAllBytes(arguments[1]);
                    string name = System.Text.Encoding.ASCII.GetString(archive, 5, archive[4] - 1);
                    int body = 5 + archive[4];
                    byte[] content = new byte[archive.Length - body];
                    Array.Copy(archive, body, content, 0, content.Length);
                    File.WriteAllBytes(Path.Combine(directory, name), content);
                    Console.WriteLine("FAKE UCC: decompressed " + name);
                    return 0;
                }
            }
            """.trimIndent()

        afterSpec { root.toFile().deleteRecursively() }

        fun csharpCompiler(): Path {
            val frameworks = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "Microsoft.NET")
            val candidates =
                listOf("Framework64", "Framework")
                    .map { frameworks / it / "v4.0.30319" / "csc.exe" }
            return candidates.firstOrNull { it.exists() }
                ?: error("No C# compiler to build the fake UCC with, looked in $candidates")
        }

        suspend fun fakeCompiler(directory: Path): Path {
            directory.createDirectories()
            val binary = directory / platform.compilerExecutable
            if (platform != WINDOWS) {
                binary.writeText(fakeCompilerScript)
                return binary
            }

            val source = directory / "FakeUcc.cs"
            source.writeText(fakeCompilerSource)
            Executable
                .build(csharpCompiler()) {
                    arguments += listOf("-nologo", "-target:exe", "-out:$binary", "$source")
                }.startAndAwait()
                .orThrow()
            return binary
        }

        var fakeCompilerCache: Path? = null

        suspend fun fakeCompiler(): Path = fakeCompilerCache ?: fakeCompiler(root / "fake-ucc").also { fakeCompilerCache = it }

        fun emptyFile(path: Path): Path = path.createParentDirectories().also { it.writeText("") }

        suspend fun installation(name: String): UtInstallation {
            val directory = root / name / "install"
            val systemDirectory = directory / UtDirectory.System.name
            emptyFile(systemDirectory / platform.gameExecutable)
            fakeCompiler().copyTo(systemDirectory / platform.compilerExecutable).toFile().setExecutable(true)
            return UtInstallation(directory, UtConfig(ConfigReader.EMPTY, DirectoriesConfig(ConfigReader.EMPTY, home = root / name)))
        }

        fun archive(path: Path): Path {
            val name = path.name.removeSuffix(".uz").toByteArray() + byteArrayOf(0)
            val header = byteArrayOf(0xD2.toByte(), 0x04, 0, 0, name.size.toByte()) + name
            return path.createParentDirectories().also { it.writeBytes(header) }
        }

        context("compressed") {
            test("returns the .uz archives the maps directory holds") {
                val installation = installation("compressed-lists")
                emptyFile(installation.directory / Maps.name / "CTF-BT-Test.unr.uz")

                val installer = UtMapInstaller(installation)

                installer.compressed().map { it.name } shouldBe listOf("CTF-BT-Test.unr.uz")
            }

            test("returns an empty list when the maps directory does not exist") {
                val installation = installation("compressed-empty")
                val installer = UtMapInstaller(installation)

                installer.compressed() shouldBe emptyList()
            }

            test("returns an empty list when the maps directory holds only plain maps") {
                val installation = installation("compressed-plain")
                emptyFile(installation.directory / Maps.name / "CTF-BT-Test.unr")
                val installer = UtMapInstaller(installation)

                installer.compressed() shouldBe emptyList()
            }
        }

        context("extract") {
            test("returns the map it wrote to the maps directory, named after the archive without its .uz suffix") {
                val installation = installation("extract-target")
                val mapsDirectory = installation.directory / Maps.name
                archive(mapsDirectory / "CTF-BT-Test.unr.uz")
                val installer = UtMapInstaller(installation)

                val extract = installer.extract(mapsDirectory / "CTF-BT-Test.unr.uz")

                extract shouldBe mapsDirectory / "CTF-BT-Test.unr"
                extract.exists() shouldBe true
            }

            test("keeps the archive in place") {
                val installation = installation("extract-archive-kept")
                val mapsDirectory = installation.directory / Maps.name
                val archive = archive(mapsDirectory / "CTF-BT-Test.unr.uz")
                val installer = UtMapInstaller(installation)

                installer.extract(archive)

                archive.exists() shouldBe true
            }
        }

        context("install") {
            test("decompresses every archive whose map does not exist and removes the archive once its map exists") {
                val installation = installation("install-fresh")
                val mapsDirectory = installation.directory / Maps.name
                archive(mapsDirectory / "CTF-BT-Test.unr.uz")
                archive(mapsDirectory / "CTF-BT-II.unr.uz")
                val installer = UtMapInstaller(installation)

                installer.install()

                (mapsDirectory / "CTF-BT-Test.unr").exists() shouldBe true
                (mapsDirectory / "CTF-BT-II.unr").exists() shouldBe true
                (mapsDirectory / "CTF-BT-Test.unr.uz").exists() shouldBe false
                (mapsDirectory / "CTF-BT-II.unr.uz").exists() shouldBe false
                installer.isInstalled() shouldBe true
            }

            test("leaves an archive in place when its map already exists") {
                val installation = installation("install-map-exists")
                val mapsDirectory = installation.directory / Maps.name
                archive(mapsDirectory / "CTF-BT-Test.unr.uz")
                emptyFile(mapsDirectory / "CTF-BT-Test.unr")
                val installer = UtMapInstaller(installation)

                installer.install()

                (mapsDirectory / "CTF-BT-Test.unr.uz").exists() shouldBe true
                installer.compressed().map { it.name } shouldBe listOf("CTF-BT-Test.unr.uz")
            }
        }
    })
