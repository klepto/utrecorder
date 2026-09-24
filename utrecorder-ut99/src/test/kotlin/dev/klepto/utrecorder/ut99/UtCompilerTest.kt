package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.io.Executable
import dev.klepto.utrecorder.common.io.startAndAwait
import dev.klepto.utrecorder.ut99.UtPlatform.LINUX
import dev.klepto.utrecorder.ut99.UtPlatform.WINDOWS
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * Tests [UtCompiler] against a fake UCC in a stub installation.
 *
 * The fake UCC is a shell script on Linux and a C# program built with the .NET Framework compiler on Windows. It writes
 * the package named by `-packages=`, writes the file named by the header of a `.uz` archive, and fails when its
 * directory holds `ucc.fail`.
 */
class UtCompilerTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utcompiler-test")
        val platform = UtPlatform.current()
        val compilerName =
            when (platform) {
                WINDOWS -> "UCC.exe"
                LINUX -> "ucc-bin"
            }

        afterSpec { root.toFile().deleteRecursively() }

        val fakeCompilerScript =
            """
            #!/bin/sh
            DIRECTORY=${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)
            echo "FAKE UCC: compiling"
            if [ -f "${'$'}DIRECTORY/ucc.fail" ]; then
                echo "FAKE UCC: simulated failure"
                exit 3
            fi
            if [ "${'$'}1" = "decompress" ]; then
                LENGTH=${'$'}(od -An -tu1 -j4 -N1 "${'$'}2" | tr -d ' ')
                NAME=${'$'}(dd if="${'$'}2" bs=1 skip=5 count=${'$'}((LENGTH - 1)) 2>/dev/null)
                touch "${'$'}DIRECTORY/${'$'}NAME"
                exit 0
            fi
            for ARGUMENT in "${'$'}@"; do
                case "${'$'}ARGUMENT" in
                    -packages=*) touch "${'$'}DIRECTORY/${'$'}{ARGUMENT#-packages=}.u" ;;
                esac
            done
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
                    Console.WriteLine("FAKE UCC: compiling");
                    if (File.Exists(Path.Combine(directory, "ucc.fail")))
                    {
                        Console.WriteLine("FAKE UCC: simulated failure");
                        return 3;
                    }

                    if (arguments.Length > 1 && arguments[0] == "decompress")
                    {
                        byte[] archive = File.ReadAllBytes(arguments[1]);
                        string name = System.Text.Encoding.ASCII.GetString(archive, 5, archive[4] - 1);
                        File.WriteAllText(Path.Combine(directory, name), "");
                        return 0;
                    }

                    foreach (string argument in arguments)
                    {
                        if (argument.StartsWith("-packages="))
                        {
                            File.WriteAllText(Path.Combine(directory, argument.Substring(10) + ".u"), "");
                        }
                    }
                    return 0;
                }
            }
            """.trimIndent()

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
            val binary = directory / compilerName
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

        suspend fun installation(
            name: String,
            compiler: Boolean = true,
            fails: Boolean = false,
        ): UtInstallation {
            val systemDirectory = root / name / "System"
            systemDirectory.createDirectories()
            (systemDirectory / platform.gameExecutable).writeText("")
            (systemDirectory / "Default.ini").writeText("[Editor.EditorEngine]\nEditPackages=Core")
            if (compiler) {
                val target = fakeCompiler().copyTo(systemDirectory / compilerName)
                target.toFile().setExecutable(true)
            }
            if (fails) {
                (systemDirectory / "ucc.fail").writeText("")
            }
            return UtInstallation(
                root / name,
                UtConfig(ConfigReader.EMPTY, DirectoriesConfig(ConfigReader.EMPTY, home = root / "$name-home")),
            )
        }

        fun sources(packageName: String): Path {
            val sourcesPath = root / "sources" / packageName
            (sourcesPath / "Classes").createDirectories()
            (sourcesPath / "Classes" / "$packageName.uc").writeText("class $packageName extends Object;")
            return sourcesPath
        }

        fun workspaces(name: String): List<Path> =
            (root / "$name-home" / "workspace").takeIf { it.exists() }?.listDirectoryEntries("ucc-*") ?: emptyList()

        test("throws naming the missing path when the installation holds no compiler executable") {
            val installation = installation("no-compiler", compiler = false)

            val exception = shouldThrow<IllegalStateException> { UtCompiler(installation) }

            exception.message shouldContain "${root / "no-compiler" / "System" / compilerName}"
        }

        context("make") {
            test("writes make.ini as Default.ini plus the configure block, with the package appended to EditPackages") {
                val compiler = installation("make-ini", fails = true).compiler

                shouldThrow<IllegalStateException> {
                    compiler.make(sources("PackageTwo"), root / "out" / "PackageTwo.u") {
                        this["Editor.EditorEngine", "UseSound"] = "False"
                    }
                }

                val ini = UtIni.read(workspaces("make-ini").single() / "make.ini")
                ini["Editor.EditorEngine", "EditPackages"] shouldBe listOf("Core", "PackageTwo")
                ini["Editor.EditorEngine", "UseSound"] shouldBe listOf("False")
            }

            test("copies the compiled package to outputPath") {
                val compiler = installation("output").compiler
                val outputPath = root / "out" / "PackageThree.u"

                val result = compiler.make(sources("PackageThree"), outputPath)

                result shouldBe outputPath
                outputPath.exists() shouldBe true
            }

            test("removes the staged package sources from the installation root after a successful run") {
                val compiler = installation("staged").compiler

                compiler.make(sources("PackageFour"), root / "out" / "PackageFour.u")

                (root / "staged" / "PackageFour").exists() shouldBe false
            }

            test("keeps the workspace of a successful run, holding the make configuration it wrote") {
                val compiler = installation("kept").compiler

                compiler.make(sources("PackageSix"), root / "out" / "PackageSix.u")

                val workspace = workspaces("kept").single()
                (workspace / "make.ini").exists() shouldBe true
                (workspace / "PackageSix.u").exists() shouldBe true
            }

            test("throws and keeps the workspace of a failed run with make.log written") {
                val compiler = installation("failure", fails = true).compiler

                shouldThrow<IllegalStateException> {
                    compiler.make(sources("PackageFive"), root / "out" / "PackageFive.u")
                }

                val workspace = workspaces("failure").single()
                (workspace / "make.log").exists() shouldBe true
                (workspace / "PackageFive.u").exists() shouldBe false
                (root / "failure" / "PackageFive").exists() shouldBe false
            }
        }

        context("extract") {
            test("moves the file named by the archive header to outputPath, whatever the archive is named") {
                val compiler = installation("extract").compiler
                val archive = root / "archives" / "Map-V1.unr.uz"
                archive.parent.createDirectories()
                archive.writeBytes(byteArrayOf(0xD2.toByte(), 0x04, 0, 0, 8) + "Map.unr".toByteArray() + byteArrayOf(0))
                val outputPath = root / "out" / "Map-V1.unr"

                val result = compiler.extract(archive, outputPath)

                result shouldBe outputPath
                outputPath.exists() shouldBe true
                (root / "extract" / "System" / "Map.unr").exists() shouldBe false
            }
        }
    })
