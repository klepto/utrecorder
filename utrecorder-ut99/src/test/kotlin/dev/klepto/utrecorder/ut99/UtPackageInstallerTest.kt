package dev.klepto.utrecorder.ut99

import com.sun.net.httpserver.HttpServer
import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.process.executable
import io.kotest.assertions.throwables.shouldThrow
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
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * The name of the file that makes the fake compiler fail.
 */
private const val COMPILER_FAILURE_MARKER = "ucc.fail"

/**
 * The directory the C# compiler of the .NET framework sits in, under the Windows directory.
 */
private const val CSHARP_COMPILER_DIRECTORY = "Microsoft.NET"

/**
 * The framework version the fake compiler is built with.
 */
private const val CSHARP_COMPILER_VERSION = "v4.0.30319"

/**
 * Tests for [UtPackageInstaller] against a fake installation, a local HTTP server standing in for a redirect and a
 * fake UCC executable standing in for the decompressor. [UtPackageInstallerIntegrationTest] covers the real ones.
 */
class UtPackageInstallerTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utpackageinstaller-test")
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val served = mutableMapOf<String, ByteArray>()
        val requests = mutableListOf<String>()

        server.createContext("/") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
            val body = served[exchange.requestURI.path]
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
        val compilerTemplate by lazy { fakeCompiler(root / "compiler") }

        fun fixture(name: String) = Fixture(root / name)

        fun redirectFixture(
            name: String,
            compilerFails: Boolean = false,
        ) = Fixture(root / name, redirects).apply { installCompiler(compilerTemplate, compilerFails) }

        fun serve(
            path: String,
            bytes: ByteArray = packageBytes(),
        ) {
            served[path] = bytes
        }

        context("redirectUrl") {
            test("builds the base, the name, the extension and .uz into one URL") {
                redirectUrl("http://redirect.test/files", "BTPog", "u") shouldBe "http://redirect.test/files/BTPog.u.uz"
            }

            test("URL-encodes a name holding a space or a plus, writing the space as %20") {
                redirectUrl("http://redirect.test/files", "CTF-BT +Fun", "unr") shouldBe
                    "http://redirect.test/files/CTF-BT%20%2BFun.unr.uz"
            }
        }

        context("install from a local source") {
            test("does not fetch a package the installation already holds at the named GUID") {
                val fixture = fixture("installation-holds")
                writePackage(fixture.installation.systemDirectory / "Engine.u")
                val installer = fixture.installer(UtPackage.named("Engine", GUID))

                installer.install()

                installer.installed shouldBe emptyList()
                (fixture.packagesDirectory / "Engine.u").exists() shouldBe false
            }

            test("copies a package the packages directory holds into the directory its extension maps to") {
                val fixture = fixture("packages-directory-holds")
                writePackage(fixture.packagesDirectory / "CTF-BT-Test.unr")
                val installer = fixture.installer(UtPackage.named("CTF-BT-Test", GUID))

                installer.install()

                val target = fixture.installationPath(UtDirectory.Maps, "CTF-BT-Test.unr")
                target.exists() shouldBe true
                installer.installed.map { it.path } shouldBe listOf(target)
            }

            test("matches a name the packages directory holds in another case") {
                val fixture = fixture("packages-directory-case")
                writePackage(fixture.packagesDirectory / "btpog.u")
                val installer = fixture.installer(UtPackage.named("BTPog", GUID))

                installer.install()

                fixture.installationPath(UtDirectory.System, "btpog.u").exists() shouldBe true
                installer.installed.map { it.name } shouldBe listOf("btpog")
            }

            test("overwrites a package the installation holds under the same name at a different GUID") {
                val fixture = fixture("overwrites-other-guid")
                writePackage(fixture.installation.systemDirectory / "Engine.u", OTHER_GUID_HEADER_BYTES)
                writePackage(fixture.packagesDirectory / "Engine.u", GUID_HEADER_BYTES)
                val installer = fixture.installer(UtPackage.named("Engine", GUID))

                installer.install()

                val target = fixture.installationPath(UtDirectory.System, "Engine.u")
                UtPackage.read(target).guid shouldBe GUID
                installer.installed.map { it.path } shouldBe listOf(target)
            }

            test("satisfies a demo entry that carries no GUID with any file of that name") {
                val fixture = redirectFixture("empty-guid-local")
                writePackage(fixture.packagesDirectory / "BTPog.u", OTHER_GUID_HEADER_BYTES)
                val installer = fixture.installer(UtPackage.named("BTPog", ""))

                installer.install()

                fixture.installationPath(UtDirectory.System, "BTPog.u").exists() shouldBe true
                requests shouldBe emptyList()
            }

            test("holds the packages it fetched in the order it fetched them") {
                val fixture = fixture("fetch-order")
                writePackage(fixture.packagesDirectory / "BTPog.u")
                writePackage(fixture.packagesDirectory / "CTF-BT-Test.unr")
                val installer =
                    fixture.installer(
                        UtPackage.named("CTF-BT-Test", GUID),
                        UtPackage.named("BTPog", GUID),
                    )

                installer.install()

                installer.installed.map { it.name } shouldBe listOf("CTF-BT-Test", "BTPog")
            }

            test("holds nothing before an install, and nothing after an install that found every package in place") {
                val fixture = fixture("nothing-fetched")
                writePackage(fixture.installation.systemDirectory / "Engine.u")
                val installer = fixture.installer(UtPackage.named("Engine", GUID))

                installer.installed shouldBe emptyList()
                installer.install()
                installer.installed shouldBe emptyList()
            }
        }

        context("install from a redirect") {
            test("fetches a package no local source holds, decompresses it and copies it into the installation") {
                val fixture = redirectFixture("fetch-from-redirect")
                serve("/first/BTPog.u.uz")
                val installer = fixture.installer(UtPackage.named("BTPog", GUID))

                installer.install()

                val target = fixture.installationPath(UtDirectory.System, "BTPog.u")
                UtPackage.read(fixture.packagesDirectory / "BTPog.u").guid shouldBe GUID
                UtPackage.read(target).guid shouldBe GUID
                installer.installed.map { it.path } shouldBe listOf(target)
            }

            test("tries the redirects in the configured order, and uses the second when the first serves nothing") {
                val fixture = redirectFixture("second-redirect")
                serve("/second/BTPog.u.uz")
                val installer = fixture.installer(UtPackage.named("BTPog", GUID))

                installer.install()

                requests.first() shouldBe "HEAD /first/BTPog.u.uz"
                requests.last() shouldBe "GET /second/BTPog.u.uz"
            }

            test("probes every package extension in UtDirectory.packages order and takes the first hit") {
                val fixture = redirectFixture("extension-order")
                serve("/first/CTF-BT-Test.unr.uz")
                val installer = fixture.installer(UtPackage.named("CTF-BT-Test", GUID))

                installer.install()

                requests shouldBe
                    listOf(
                        "HEAD /first/CTF-BT-Test.u.uz",
                        "HEAD /first/CTF-BT-Test.unr.uz",
                        "GET /first/CTF-BT-Test.unr.uz",
                    )
                fixture.installationPath(UtDirectory.Maps, "CTF-BT-Test.unr").exists() shouldBe true
            }

            test("discards a local file of the right name at the wrong GUID and tries the next source") {
                val fixture = redirectFixture("wrong-guid-locally")
                writePackage(fixture.packagesDirectory / "BTPog.u", OTHER_GUID_HEADER_BYTES)
                serve("/first/BTPog.u.uz", packageBytes(GUID_HEADER_BYTES))
                val installer = fixture.installer(UtPackage.named("BTPog", GUID))

                installer.install()

                requests.last() shouldBe "GET /first/BTPog.u.uz"
                UtPackage.read(fixture.installationPath(UtDirectory.System, "BTPog.u")).guid shouldBe GUID
            }

            test("does not check the GUID of a package it fetched for a demo entry that carries none") {
                val fixture = redirectFixture("empty-guid-fetched")
                serve("/first/BTPog.u.uz", packageBytes(OTHER_GUID_HEADER_BYTES))
                val installer = fixture.installer(UtPackage.named("BTPog", ""))

                installer.install()

                val target = fixture.installationPath(UtDirectory.System, "BTPog.u")
                UtPackage.read(target).guid shouldBe OTHER_GUID
                installer.installed.map { it.path } shouldBe listOf(target)
            }

            test("deletes the archive it fetched once it is decompressed") {
                val fixture = redirectFixture("archive-deleted")
                serve("/first/BTPog.u.uz")
                val installer = fixture.installer(UtPackage.named("BTPog", GUID))

                installer.install()

                fixture.packagesDirectory.listDirectoryEntries().map { it.name } shouldBe listOf("BTPog.u")
            }

            test("leaves nothing in the packages directory when the decompression fails") {
                val fixture = redirectFixture("decompression-fails", compilerFails = true)
                serve("/first/BTPog.u.uz")
                val installer = fixture.installer(UtPackage.named("BTPog", GUID))

                shouldThrow<IllegalStateException> { installer.install() }

                fixture.packagesDirectory.listDirectoryEntries() shouldBe emptyList()
                installer.installed shouldBe emptyList()
            }
        }

        context("a package no source holds") {
            test("throws naming the package and every source that was tried") {
                val fixture = redirectFixture("resolves-nowhere")
                val installer = fixture.installer(UtPackage.named("BTPog", GUID))

                val exception = shouldThrow<UtPackageException> { installer.install() }

                exception.packageName shouldBe "BTPog"
                exception.sources shouldBe listOf("${fixture.gameDirectory}", "${fixture.packagesDirectory}") + redirects
            }

            test("keeps what it already fetched, on disk and in installed, and fetches nothing after the failure") {
                val fixture = redirectFixture("fails-partway")
                writePackage(fixture.packagesDirectory / "Engine.u")
                serve("/first/BTPog.u.uz")
                val installer =
                    fixture.installer(
                        UtPackage.named("Engine", GUID),
                        UtPackage.named("Missing", GUID),
                        UtPackage.named("BTPog", GUID),
                    )

                shouldThrow<UtPackageException> { installer.install() }

                installer.installed.map { it.name } shouldBe listOf("Engine")
                fixture.installationPath(UtDirectory.System, "Engine.u").exists() shouldBe true
                requests.none { it.contains("BTPog") } shouldBe true
            }
        }

        context("isInstalled") {
            test("is true when the installation holds every named package at the named GUID") {
                val fixture = fixture("is-installed-true")
                writePackage(fixture.installationPath(UtDirectory.System, "Engine.u"))
                writePackage(fixture.installationPath(UtDirectory.Maps, "CTF-BT-Test.unr"))
                val installer =
                    fixture.installer(
                        UtPackage.named("Engine", GUID),
                        UtPackage.named("CTF-BT-Test", GUID),
                    )

                installer.isInstalled() shouldBe true
            }

            test("is false when a named package is missing") {
                val fixture = fixture("is-installed-missing")
                writePackage(fixture.installationPath(UtDirectory.System, "Engine.u"))
                val installer =
                    fixture.installer(
                        UtPackage.named("Engine", GUID),
                        UtPackage.named("BTPog", GUID),
                    )

                installer.isInstalled() shouldBe false
            }

            test("is false when a named package is present at a different GUID") {
                val fixture = fixture("is-installed-other-guid")
                writePackage(fixture.installationPath(UtDirectory.System, "Engine.u"), OTHER_GUID_HEADER_BYTES)
                val installer = fixture.installer(UtPackage.named("Engine", GUID))

                installer.isInstalled() shouldBe false
            }
        }

        context("uninstall") {
            test("deletes the packages this installer fetched and leaves the ones the installation already held") {
                val fixture = fixture("uninstall-fetched")
                writePackage(fixture.installation.systemDirectory / "Engine.u")
                writePackage(fixture.packagesDirectory / "BTPog.u")
                val installer =
                    fixture.installer(
                        UtPackage.named("Engine", GUID),
                        UtPackage.named("BTPog", GUID),
                    )

                installer.install()
                installer.uninstall()

                fixture.installationPath(UtDirectory.System, "BTPog.u").exists() shouldBe false
                fixture.installationPath(UtDirectory.System, "Engine.u").exists() shouldBe true
            }

            test("deletes nothing when it runs before an install") {
                val fixture = fixture("uninstall-before-install")
                writePackage(fixture.installation.systemDirectory / "Engine.u")
                val installer = fixture.installer(UtPackage.named("Engine", GUID))

                installer.uninstall()

                fixture.installationPath(UtDirectory.System, "Engine.u").exists() shouldBe true
            }
        }
    })

/**
 * A fake installation rooted at [home], holding the game executable [UtInstallation] checks for and nothing else, and
 * the configuration naming its packages directory and the [redirects] a package is fetched from.
 *
 * @property home The directory the installation, the packages directory and the task directories sit under.
 * @param redirects The base URLs the installer probes, in the order it probes them.
 */
private class Fixture(
    val home: Path,
    redirects: List<String> = emptyList(),
) {
    /**
     * The root of the fake installation.
     */
    val gameDirectory: Path = home / "install"

    /**
     * The configuration the installer runs against.
     */
    val config =
        UtConfig(
            directories = Directories(home),
            gameDirectory = gameDirectory,
            redirectUrls = redirects,
        )

    /**
     * The directory a fetched package is decompressed into.
     */
    val packagesDirectory: Path = config.packagesDirectory

    /**
     * The installation the packages are provisioned into.
     */
    val installation: UtInstallation

    init {
        installationPath(UtDirectory.System, UT_PLATFORM.gameExecutable).createParentDirectories().writeText("")
        packagesDirectory.createDirectories()
        installation = UtInstallation(gameDirectory, Workspace(directory = home / "tasks"))
    }

    /**
     * Returns the path a file named [fileName] takes in the [directory] of this installation.
     */
    fun installationPath(
        directory: UtDirectory,
        fileName: String,
    ): Path = gameDirectory / directory.name / fileName

    /**
     * Puts a copy of the fake compiler [template] where this installation resolves its compiler, failing every run
     * of it when [fails] is set.
     */
    fun installCompiler(
        template: Path,
        fails: Boolean = false,
    ) {
        val target = installationPath(UtDirectory.System, UT_PLATFORM.compilerExecutable)
        template.copyTo(target, overwrite = true)
        target.toFile().setExecutable(true)
        if (fails) {
            installationPath(UtDirectory.System, COMPILER_FAILURE_MARKER).writeText("")
        }
    }

    /**
     * Returns the installer of the given [packages] against this installation.
     */
    fun installer(vararg packages: UtPackage) = UtPackageInstaller(packages.toList(), installation, config)
}

/**
 * Builds the fake compiler under [directory] and returns it. Asked to decompress an archive, it copies the archive to
 * the file it is named after, next to itself, which is where UCC leaves what it decompressed. A file named
 * [COMPILER_FAILURE_MARKER] beside it makes it fail without producing anything.
 *
 * Windows starts a program by its content rather than by its name, and an installation resolves its compiler as
 * `UCC.exe`, so the fake has to be a real executable there. The C# compiler of the .NET framework, which a Windows
 * installation carries, builds one. A shell script under the compiler's name is enough everywhere else.
 */
private fun fakeCompiler(directory: Path): Path {
    directory.createDirectories()
    val binary = directory / UT_PLATFORM.compilerExecutable
    if (UT_PLATFORM != UtPlatform.WINDOWS) {
        binary.writeText(FAKE_COMPILER_SCRIPT)
        binary.toFile().setExecutable(true)
        return binary
    }

    val source = directory / "FakeUcc.cs"
    source.writeText(FAKE_COMPILER_SOURCE)
    executable(csharpCompiler()) {
        arguments += listOf("-nologo", "-target:exe", "-out:$binary", "$source")
    }.startAndAwait().orThrow()
    return binary
}

/**
 * Returns the C# compiler the fake compiler is built with.
 */
private fun csharpCompiler(): Path {
    val frameworks = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", CSHARP_COMPILER_DIRECTORY)
    val candidates =
        listOf("Framework64", "Framework").map { frameworks / it / CSHARP_COMPILER_VERSION / "csc.exe" }
    return candidates.firstOrNull { it.exists() } ?: error("No C# compiler to build the fake UCC with, looked in $candidates")
}

/**
 * The shell script the fake compiler is on every platform but Windows.
 */
private val FAKE_COMPILER_SCRIPT =
    """
    #!/bin/sh
    DIRECTORY=${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)
    if [ -f "${'$'}DIRECTORY/$COMPILER_FAILURE_MARKER" ]; then
        echo "FAKE UCC: simulated failure"
        exit 3
    fi
    NAME=${'$'}(basename "${'$'}2" .uz)
    cp "${'$'}2" "${'$'}DIRECTORY/${'$'}NAME"
    echo "FAKE UCC: decompressed ${'$'}NAME"
    exit 0
    """.trimIndent()

/**
 * The C# program the fake compiler is built from on Windows.
 */
private val FAKE_COMPILER_SOURCE =
    """
    using System;
    using System.IO;

    class FakeUcc
    {
        static int Main(string[] arguments)
        {
            string directory = AppDomain.CurrentDomain.BaseDirectory;
            if (File.Exists(Path.Combine(directory, "$COMPILER_FAILURE_MARKER")))
            {
                Console.WriteLine("FAKE UCC: simulated failure");
                return 3;
            }

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
