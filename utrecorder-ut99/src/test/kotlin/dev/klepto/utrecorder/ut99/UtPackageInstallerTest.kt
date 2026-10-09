package dev.klepto.utrecorder.ut99

import com.sun.net.httpserver.HttpServer
import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.io.Executable
import dev.klepto.utrecorder.common.io.startAndAwait
import dev.klepto.utrecorder.common.io.workspace
import dev.klepto.utrecorder.ut99.UtDirectory.Maps
import dev.klepto.utrecorder.ut99.UtPlatform.WINDOWS
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * Tests [UtPackageInstaller] against a fake UCC in a stub installation and a local HTTP server that serves two redirects.
 *
 * The fake UCC is a shell script on Linux and a C# program built with the .NET Framework compiler on Windows. It writes
 * the file named by the header of a `.uz` archive, holding the bytes that follow the header, and fails when its
 * directory holds a `ucc.fail` file. The server records every request it gets and answers from the files a case serves.
 */
class UtPackageInstallerTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utpackageinstaller-test")
        val platform = UtPlatform.current()
        val guid = "123456789ABCDEF0876543210BADF00D"
        val otherGuid = "11111111222222223333333344444444"
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val served = mutableMapOf<String, ByteArray>()
        val requests = mutableListOf<String>()

        server.createContext("/") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI.rawPath}"
            val body = served[exchange.requestURI.rawPath]
            try {
                when {
                    body == null -> {
                        exchange.sendResponseHeaders(404, -1)
                    }

                    exchange.requestMethod == "HEAD" -> {
                        exchange.responseHeaders.set("Content-Length", "${body.size}")
                        exchange.sendResponseHeaders(200, -1)
                    }

                    else -> {
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                }
            } finally {
                exchange.close()
            }
        }
        server.start()

        afterSpec {
            server.stop(0)
            root.toFile().deleteRecursively()
        }

        beforeTest {
            served.clear()
            requests.clear()
        }

        val address = "http://${server.address.hostString}:${server.address.port}"
        val redirects = listOf("$address/first", "$address/second")

        val fakeCompilerScript =
            """
            #!/bin/sh
            DIRECTORY=${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)
            if [ -f "${'$'}DIRECTORY/ucc.fail" ]; then
                echo "FAKE UCC: simulated failure"
                exit 3
            fi
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
                    if (File.Exists(Path.Combine(directory, "ucc.fail")))
                    {
                        Console.WriteLine("FAKE UCC: simulated failure");
                        return 3;
                    }

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

        fun packagesDirectory(name: String): Path = DirectoriesConfig(ConfigReader.EMPTY, home = root / name).packages

        fun emptyFile(path: Path): Path = path.createParentDirectories().also { it.writeText("") }

        suspend fun installation(
            name: String,
            compilerFails: Boolean = false,
        ): UtInstallation {
            val directory = root / name / "install"
            val systemDirectory = directory / UtDirectory.System.name
            packagesDirectory(name).createDirectories()
            emptyFile(systemDirectory / platform.gameExecutable)
            fakeCompiler().copyTo(systemDirectory / platform.compilerExecutable).toFile().setExecutable(true)
            if (compilerFails) {
                emptyFile(systemDirectory / "ucc.fail")
            }
            return UtInstallation(directory, UtConfig(ConfigReader.EMPTY, DirectoriesConfig(ConfigReader.EMPTY, home = root / name)))
        }

        fun installer(
            installation: UtInstallation,
            vararg packages: UtPackage,
        ): UtPackageInstaller {
            val home = installation.directory.parent
            val config =
                UtConfig(
                    ConfigReader.EMPTY,
                    directoriesConfig = DirectoriesConfig(ConfigReader.EMPTY, home = home),
                    gameDirectory = installation.directory,
                    redirectUrls = redirects,
                )
            return UtPackageInstaller(packages.toList(), installation, config)
        }

        fun installationPath(
            installation: UtInstallation,
            directory: UtDirectory,
            fileName: String,
        ): Path = installation.directory / directory.name / fileName

        fun packageBytes(guid: String): ByteArray {
            val header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(0x9E2A83C1.toInt()).putShort(68)
            guid.chunked(8).forEachIndexed { index, word -> header.putInt(36 + index * 4, word.toLong(16).toInt()) }
            return header.array()
        }

        fun archive(
            name: String,
            content: ByteArray,
        ): ByteArray {
            val header = name.toByteArray() + byteArrayOf(0)
            return byteArrayOf(0xD2.toByte(), 0x04, 0, 0, header.size.toByte()) + header + content
        }

        fun writePackage(
            path: Path,
            guid: String,
        ): Path = path.createParentDirectories().also { it.writeBytes(packageBytes(guid)) }

        context("install from a local source") {
            test("does not fetch a package the installation already holds") {
                val installation = installation("installation-holds")
                emptyFile(installation.systemDirectory / "Engine.u")
                val installer = installer(installation, UtPackage.named("Engine", ""))

                installer.install()

                installer.installed shouldBe emptyList()
                (packagesDirectory("installation-holds") / "Engine.u").exists() shouldBe false
            }

            test("copies a package the packages directory holds into the directory its extension maps to") {
                val installation = installation("packages-directory-holds")
                emptyFile(packagesDirectory("packages-directory-holds") / "CTF-BT-Test.unr")
                val installer = installer(installation, UtPackage.named("CTF-BT-Test", ""))

                installer.install()

                val target = installationPath(installation, Maps, "CTF-BT-Test.unr")
                target.exists() shouldBe true
                installer.installed.map { it.path } shouldBe listOf(target)
            }

            test("leaves the package it copied in the packages directory, also after uninstall") {
                val installation = installation("packages-directory-kept")
                writePackage(packagesDirectory("packages-directory-kept") / "BTPog.u", guid)
                val installer = installer(installation, UtPackage.named("BTPog", guid))

                installer.install()
                installer.uninstall()

                UtPackage.read(packagesDirectory("packages-directory-kept") / "BTPog.u").guid() shouldBe
                    "123456789ABCDEF0876543210BADF00D"
            }

            test("matches a name the packages directory holds in another case") {
                val installation = installation("packages-directory-case")
                emptyFile(packagesDirectory("packages-directory-case") / "btpog.u")
                val installer = installer(installation, UtPackage.named("BTPog", ""))

                installer.install()

                installationPath(installation, UtDirectory.System, "btpog.u").exists() shouldBe true
                installer.installed.map { it.name } shouldBe listOf("btpog")
            }

            test("overwrites a package the installation holds under the same name at another GUID") {
                val installation = installation("overwrites-other-guid")
                writePackage(installation.systemDirectory / "Engine.u", otherGuid)
                writePackage(packagesDirectory("overwrites-other-guid") / "Engine.u", guid)
                val installer = installer(installation, UtPackage.named("Engine", guid))

                installer.install()

                val target = installationPath(installation, UtDirectory.System, "Engine.u")
                UtPackage.read(target).guid() shouldBe "123456789ABCDEF0876543210BADF00D"
                installer.installed.map { it.path } shouldBe listOf(target)
            }

            test("copies a file of the package name at any GUID when the demo names no GUID") {
                val installation = installation("empty-guid-local")
                emptyFile(packagesDirectory("empty-guid-local") / "BTPog.u")
                val installer = installer(installation, UtPackage.named("BTPog", ""))

                installer.install()

                installationPath(installation, UtDirectory.System, "BTPog.u").exists() shouldBe true
                requests shouldBe emptyList()
            }

            test("installed holds the packages this installer fetched, in the order it fetched them") {
                val installation = installation("fetch-order")
                emptyFile(packagesDirectory("fetch-order") / "BTPog.u")
                emptyFile(packagesDirectory("fetch-order") / "CTF-BT-Test.unr")
                val installer =
                    installer(
                        installation,
                        UtPackage.named("CTF-BT-Test", ""),
                        UtPackage.named("BTPog", ""),
                    )

                installer.install()

                installer.installed.map { it.name } shouldBe listOf("CTF-BT-Test", "BTPog")
            }

            test("installed is empty before install runs and after an install that found every package already in place") {
                val installation = installation("nothing-fetched")
                emptyFile(installation.systemDirectory / "Engine.u")
                val installer = installer(installation, UtPackage.named("Engine", ""))

                installer.installed shouldBe emptyList()
                installer.install()
                installer.installed shouldBe emptyList()
            }
        }

        context("install from a redirect") {
            test("fetches a package named CTF-BT +Fun from a redirect that serves it only at its encoded name") {
                val installation = installation("encoded-name")
                served["/first/CTF-BT%20%2BFun.unr.uz"] = archive("CTF-BT +Fun.unr", "CTF-BT +Fun".toByteArray())
                val installer = installer(installation, UtPackage.named("CTF-BT +Fun", ""))

                installer.install()

                installationPath(installation, Maps, "CTF-BT +Fun.unr").readText() shouldBe "CTF-BT +Fun"
            }

            test("writes a package it fetched into the installation and nothing into the packages directory") {
                val installation = installation("fetched-into-installation")
                served["/first/BTPog.u.uz"] = archive("BTPog.u", "BTPog".toByteArray())
                val installer = installer(installation, UtPackage.named("BTPog", ""))

                installer.install()

                installationPath(installation, UtDirectory.System, "BTPog.u").readText() shouldBe "BTPog"
                packagesDirectory("fetched-into-installation").listDirectoryEntries() shouldBe emptyList()
            }

            test("tries the redirects in the configured order, and uses the second when the first serves nothing") {
                val installation = installation("second-redirect")
                served["/second/BTPog.u.uz"] = archive("BTPog.u", "BTPog".toByteArray())
                val installer = installer(installation, UtPackage.named("BTPog", ""))

                installer.install()

                requests.first() shouldBe "HEAD /first/BTPog.u.uz"
                requests.last() shouldBe "GET /second/BTPog.u.uz"
            }

            test("tries the package extensions in lookup order and downloads the first one the redirect serves") {
                val installation = installation("extension-order")
                served["/first/CTF-BT-Test.unr.uz"] = archive("CTF-BT-Test.unr", "CTF-BT-Test".toByteArray())
                val installer = installer(installation, UtPackage.named("CTF-BT-Test", ""))

                installer.install()

                requests shouldBe
                    listOf(
                        "HEAD /first/CTF-BT-Test.u.uz",
                        "HEAD /first/CTF-BT-Test.unr.uz",
                        "GET /first/CTF-BT-Test.unr.uz",
                    )
                installationPath(installation, Maps, "CTF-BT-Test.unr").exists() shouldBe true
            }

            test("passes over a package the packages directory holds at another GUID and downloads it from a redirect") {
                val installation = installation("wrong-guid-locally")
                writePackage(packagesDirectory("wrong-guid-locally") / "BTPog.u", otherGuid)
                served["/first/BTPog.u.uz"] = archive("BTPog.u", packageBytes(guid))
                val installer = installer(installation, UtPackage.named("BTPog", guid))

                installer.install()

                requests.last() shouldBe "GET /first/BTPog.u.uz"
                UtPackage.read(installationPath(installation, UtDirectory.System, "BTPog.u")).guid() shouldBe
                    "123456789ABCDEF0876543210BADF00D"
            }

            test("throws naming the package and keeps nothing when every redirect serves it at another GUID") {
                val installation = installation("mismatched-everywhere")
                served["/first/BTPog.u.uz"] = archive("BTPog.u", packageBytes(otherGuid))
                served["/second/BTPog.u.uz"] = archive("BTPog.u", packageBytes(otherGuid))
                val installer = installer(installation, UtPackage.named("BTPog", guid))

                val exception = shouldThrow<UtPackageException> { installer.install() }

                exception.packageName shouldBe "BTPog"
                exception.guid shouldBe "123456789ABCDEF0876543210BADF00D"
                exception.message shouldBe
                    "No source holds BTPog at GUID 123456789ABCDEF0876543210BADF00D, tried: " +
                    "${root / "mismatched-everywhere" / "install"}, ${packagesDirectory("mismatched-everywhere")}, " +
                    redirects.joinToString()
                installer.installed shouldBe emptyList()
                installationPath(installation, UtDirectory.System, "BTPog.u").exists() shouldBe false
                packagesDirectory("mismatched-everywhere").listDirectoryEntries() shouldBe emptyList()
            }

            test("installs a package downloaded at the recorded GUID under its own name when it runs inside a workspace") {
                val installation = installation("inside-workspace")
                served["/first/CTF-BT-Test.unr.uz"] = archive("CTF-BT-Test.unr", packageBytes(guid))
                val installer = installer(installation, UtPackage.named("CTF-BT-Test", guid))
                val directories = DirectoriesConfig(ConfigReader.EMPTY, home = root / "inside-workspace")

                workspace("recording", config = directories) { installer.install() }

                val target = installationPath(installation, Maps, "CTF-BT-Test.unr")
                UtPackage.read(target).guid() shouldBe "123456789ABCDEF0876543210BADF00D"
                installer.installed.map { it.path } shouldBe listOf(target)
            }

            test("keeps a downloaded package at any GUID when the demo names no GUID") {
                val installation = installation("empty-guid-fetched")
                served["/first/BTPog.u.uz"] = archive("BTPog.u", packageBytes(otherGuid))
                val installer = installer(installation, UtPackage.named("BTPog", ""))

                installer.install()

                val target = installationPath(installation, UtDirectory.System, "BTPog.u")
                UtPackage.read(target).guid() shouldBe "11111111222222223333333344444444"
                installer.installed.map { it.path } shouldBe listOf(target)
            }

            test("leaves nothing in the installation or the packages directory when the decompression fails") {
                val installation = installation("decompression-fails", compilerFails = true)
                served["/first/BTPog.u.uz"] = archive("BTPog.u", "BTPog".toByteArray())
                val installer = installer(installation, UtPackage.named("BTPog", ""))

                shouldThrow<IllegalStateException> { installer.install() }

                installationPath(installation, UtDirectory.System, "BTPog.u").exists() shouldBe false
                packagesDirectory("decompression-fails").listDirectoryEntries() shouldBe emptyList()
                installer.installed shouldBe emptyList()
            }
        }

        context("a package no source holds") {
            test("throws naming the package and every source that was tried") {
                val installation = installation("resolves-nowhere")
                val installer = installer(installation, UtPackage.named("BTPog", ""))

                val exception = shouldThrow<UtPackageException> { installer.install() }

                exception.packageName shouldBe "BTPog"
                exception.guid shouldBe ""
                exception.message shouldBe
                    "No source holds BTPog, tried: ${root / "resolves-nowhere" / "install"}, " +
                    "${packagesDirectory("resolves-nowhere")}, ${redirects.joinToString()}"
                exception.sources shouldBe
                    listOf("${root / "resolves-nowhere" / "install"}", "${packagesDirectory("resolves-nowhere")}") + redirects
            }

            test("keeps what it already fetched, on disk and in installed, and fetches nothing after the failure") {
                val installation = installation("fails-partway")
                emptyFile(packagesDirectory("fails-partway") / "Engine.u")
                served["/first/BTPog.u.uz"] = archive("BTPog.u", "BTPog".toByteArray())
                val installer =
                    installer(
                        installation,
                        UtPackage.named("Engine", ""),
                        UtPackage.named("Missing", ""),
                        UtPackage.named("BTPog", ""),
                    )

                shouldThrow<UtPackageException> { installer.install() }

                installer.installed.map { it.name } shouldBe listOf("Engine")
                installationPath(installation, UtDirectory.System, "Engine.u").exists() shouldBe true
                requests.none { it.contains("BTPog") } shouldBe true
            }
        }

        context("isInstalled") {
            test("is true when the installation holds every named package") {
                val installation = installation("is-installed-true")
                emptyFile(installationPath(installation, UtDirectory.System, "Engine.u"))
                emptyFile(installationPath(installation, Maps, "CTF-BT-Test.unr"))
                val installer =
                    installer(
                        installation,
                        UtPackage.named("Engine", ""),
                        UtPackage.named("CTF-BT-Test", ""),
                    )

                installer.isInstalled() shouldBe true
            }

            test("is false when a named package is missing") {
                val installation = installation("is-installed-missing")
                emptyFile(installationPath(installation, UtDirectory.System, "Engine.u"))
                val installer =
                    installer(
                        installation,
                        UtPackage.named("Engine", ""),
                        UtPackage.named("BTPog", ""),
                    )

                installer.isInstalled() shouldBe false
            }

            test("is false when the installation holds a named package at another GUID") {
                val installation = installation("is-installed-other-guid")
                writePackage(installationPath(installation, UtDirectory.System, "Engine.u"), otherGuid)
                val installer = installer(installation, UtPackage.named("Engine", guid))

                installer.isInstalled() shouldBe false
            }
        }

        context("uninstall") {
            test("deletes the packages this installer fetched and leaves the ones the installation already held") {
                val installation = installation("uninstall-fetched")
                emptyFile(installation.systemDirectory / "Engine.u")
                emptyFile(packagesDirectory("uninstall-fetched") / "BTPog.u")
                val installer =
                    installer(
                        installation,
                        UtPackage.named("Engine", ""),
                        UtPackage.named("BTPog", ""),
                    )

                installer.install()
                installer.uninstall()

                installationPath(installation, UtDirectory.System, "BTPog.u").exists() shouldBe false
                installationPath(installation, UtDirectory.System, "Engine.u").exists() shouldBe true
            }

            test("deletes nothing when it runs before an install") {
                val installation = installation("uninstall-before-install")
                emptyFile(installation.systemDirectory / "Engine.u")
                val installer = installer(installation, UtPackage.named("Engine", ""))

                installer.uninstall()

                installationPath(installation, UtDirectory.System, "Engine.u").exists() shouldBe true
            }
        }
    })
