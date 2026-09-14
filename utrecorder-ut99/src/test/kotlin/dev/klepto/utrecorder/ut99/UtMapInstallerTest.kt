package dev.klepto.utrecorder.ut99

import com.sun.net.httpserver.HttpServer
import dev.klepto.utrecorder.common.process.Executable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.writeText

class UtMapInstallerTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utmapinstaller-test").also { root ->
            root.toFile().deleteRecursively()
            root.toFile().mkdirs()
        }
        val platform = UtPlatform.current()

        val fakeCompilerScript =
            """
            #!/bin/sh
            DIRECTORY=${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)
            NAME=${'$'}(basename "${'$'}2" .uz)
            cp "${'$'}2" "${'$'}DIRECTORY/${'$'}NAME"
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
                    string name = Path.GetFileName(arguments[1]);
                    if (name.EndsWith(".uz", StringComparison.OrdinalIgnoreCase))
                    {
                        name = name.Substring(0, name.Length - 3);
                    }

                    File.Copy(arguments[1], Path.Combine(directory, name), true);
                    Console.WriteLine("FAKE UCC: decompressed " + name);
                    return 0;
                }
            }
            """.trimIndent()

        afterSpec { root.toFile().deleteRecursively() }

        fun csharpCompiler(): Path {
            val frameworks = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "Microsoft.NET")
            val candidates = listOf("Framework64", "Framework").map { frameworks / it / "v4.0.30319" / "csc.exe" }
            return candidates.firstOrNull { it.exists() } ?: error("No C# compiler to build the fake UCC with, looked in $candidates")
        }

        fun fakeCompiler(directory: Path): Path {
            directory.createDirectories()
            val binary = directory / platform.compilerExecutable
            if (platform != UtPlatform.WINDOWS) {
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

        val fakeCompiler by lazy { fakeCompiler(root / "fake-ucc") }

        fun installation(name: String): UtInstallation {
            val systemDirectory = root / name / "install" / UtDirectory.System.name
            systemDirectory.createDirectories()
            (systemDirectory / platform.gameExecutable).writeText("")
            fakeCompiler.copyTo(systemDirectory / platform.compilerExecutable).toFile().setExecutable(true)
            return UtInstallation(root / name / "install", dev.klepto.utrecorder.common.config.Directories(root / name))
        }

        fun installer(installation: UtInstallation): UtMapInstaller = UtMapInstaller(installation)

        fun emptyFile(path: Path): Path = path.createParentDirectories().also { it.writeText("") }

        context("compressed") {
            test("lists the .uz archives a maps directory holds") {
                val installation = installation("compressed-lists")
                emptyFile(installation.directory / UtDirectory.Maps.name / "CTF-BT-Test.unr.uz")

                val installer = installer(installation)

                installer.compressed().map { it.name } shouldBe listOf("CTF-BT-Test.unr.uz")
            }

            test("lists nothing for a maps directory that is not there") {
                val installation = installation("compressed-empty")
                val installer = installer(installation)

                installer.compressed() shouldBe emptyList()
            }

            test("lists nothing for a maps directory holding only plain maps") {
                val installation = installation("compressed-plain")
                emptyFile(installation.directory / UtDirectory.Maps.name / "CTF-BT-Test.unr")
                val installer = installer(installation)

                installer.compressed() shouldBe emptyList()
            }
        }

        context("extract") {
            test("writes the archive's map into the maps directory under its own name without the .uz suffix") {
                val installation = installation("extract-target")
                val mapsDirectory = installation.directory / UtDirectory.Maps.name
                emptyFile(mapsDirectory / "CTF-BT-Test.unr.uz")
                val installer = installer(installation)

                val extract = installer.extract(mapsDirectory / "CTF-BT-Test.unr.uz")

                extract shouldBe mapsDirectory / "CTF-BT-Test.unr"
                extract.exists() shouldBe true
            }

            test("leaves the archive it extracted in place") {
                val installation = installation("extract-archive-kept")
                val mapsDirectory = installation.directory / UtDirectory.Maps.name
                val archive = emptyFile(mapsDirectory / "CTF-BT-Test.unr.uz")
                val installer = installer(installation)

                installer.extract(archive)

                archive.exists() shouldBe true
            }
        }

        context("install") {
            test("decompresses every archive holding no map and removes the archive once its map exists") {
                val installation = installation("install-fresh")
                val mapsDirectory = installation.directory / UtDirectory.Maps.name
                emptyFile(mapsDirectory / "CTF-BT-Test.unr.uz")
                emptyFile(mapsDirectory / "CTF-BT-II.unr.uz")
                val installer = installer(installation)

                installer.install()

                (mapsDirectory / "CTF-BT-Test.unr").exists() shouldBe true
                (mapsDirectory / "CTF-BT-II.unr").exists() shouldBe true
                (mapsDirectory / "CTF-BT-Test.unr.uz").exists() shouldBe false
                (mapsDirectory / "CTF-BT-II.unr.uz").exists() shouldBe false
                installer.isInstalled() shouldBe true
            }

            test("leaves an archive whose decompressed map already exists alone and stays listed") {
                val installation = installation("install-map-exists")
                val mapsDirectory = installation.directory / UtDirectory.Maps.name
                emptyFile(mapsDirectory / "CTF-BT-Test.unr.uz")
                emptyFile(mapsDirectory / "CTF-BT-Test.unr")
                val installer = installer(installation)

                installer.install()

                (mapsDirectory / "CTF-BT-Test.unr.uz").exists() shouldBe true
                installer.compressed().map { it.name } shouldBe listOf("CTF-BT-Test.unr.uz")
            }
        }
    })
