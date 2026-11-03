package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.OperatingSystem
import dev.klepto.utrecorder.common.OperatingSystem.WINDOWS
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestCase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Tests [UtPackageCache] against a stub installation whose packages are copied from the packages directory, with a
 * clock each case moves by hand.
 */
class UtPackageCacheTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utpackagecache-test")
        val platform = UtPlatform.current()
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val windowsOnly = { _: TestCase -> OperatingSystem.current() == WINDOWS }

        afterSpec { root.toFile().deleteRecursively() }

        /**
         * A clock reporting [now], which a case moves by hand, that records [label] on every reading into [readings].
         */
        class ManualClock(
            var now: Instant = start,
            private val label: String = "",
            private val readings: MutableList<String> = mutableListOf(),
        ) : Clock {
            override fun now(): Instant {
                readings += label
                return now
            }
        }

        fun file(
            path: Path,
            content: String = "",
        ): Path = path.createParentDirectories().also { it.writeText(content) }

        fun config(name: String): UtConfig =
            UtConfig(ConfigReader.EMPTY, DirectoriesConfig(ConfigReader.EMPTY, home = root / name), redirectUrls = emptyList())

        fun installation(name: String): UtInstallation {
            val config = config(name)
            file(config.gameDirectory / UtDirectory.System.name / platform.gameExecutable)
            config.directoriesConfig.packages.createDirectories()
            return UtInstallation(config.gameDirectory, config)
        }

        fun manifest(
            installation: UtInstallation,
            vararg paths: String,
        ) {
            file(installation.directory / ".utrecorder.base", paths.joinToString("") { "$it\n" })
        }

        fun maps(installation: UtInstallation): Path = installation.directory / UtDirectory.Maps.name

        fun system(installation: UtInstallation): Path = installation.systemDirectory

        fun sourcePackage(
            installation: UtInstallation,
            fileName: String,
        ): Path = file(installation.config.directoriesConfig.packages / fileName, fileName)

        context("resolve") {
            test("copies a package the packages directory holds into the installation") {
                val installation = installation("resolve-copies")
                manifest(installation)
                sourcePackage(installation, "CTF-BT-Test.unr")

                UtPackageCache(installation, clock = ManualClock()).resolve(listOf(UtPackage.named("CTF-BT-Test", "")))

                (maps(installation) / "CTF-BT-Test.unr").readText() shouldBe "CTF-BT-Test.unr"
            }

            test("throws UtPackageException naming a package no source holds") {
                val installation = installation("resolve-missing")
                manifest(installation)

                val exception =
                    shouldThrow<UtPackageException> {
                        UtPackageCache(installation, clock = ManualClock()).resolve(listOf(UtPackage.named("Missing", "")))
                    }

                exception.packageName shouldBe "Missing"
            }

            test("reports the share of packages resolved to onProgress") {
                val installation = installation("resolve-progress")
                manifest(installation)
                sourcePackage(installation, "BTPog.u")
                val reported = mutableListOf<Double>()

                UtPackageCache(installation, clock = ManualClock())
                    .resolve(listOf(UtPackage.named("BTPog", ""))) { reported += it.progress }

                reported.last() shouldBe 1.0
            }

            test("records the use of a package it fetched, so it expires one TTL after the resolve") {
                val installation = installation("resolve-records-fetched")
                manifest(installation)
                sourcePackage(installation, "BTPog.u")
                val clock = ManualClock()
                val cache = UtPackageCache(installation, clock = clock)

                cache.resolve(listOf(UtPackage.named("BTPog", "")))
                clock.now = start + 3.days
                cache.evict()
                (system(installation) / "BTPog.u").shouldExist()
                clock.now = start + 3.days + 1.seconds
                cache.evict()

                (system(installation) / "BTPog.u").shouldNotExist()
            }

            test("records the use of a package the installation held already") {
                val installation = installation("resolve-records-held")
                manifest(installation)
                file(maps(installation) / "CTF-BT-Held.unr")
                val clock = ManualClock()
                val cache = UtPackageCache(installation, clock = clock)

                cache.evict()
                clock.now = start + 2.days
                cache.resolve(listOf(UtPackage.named("CTF-BT-Held", "")))
                clock.now = start + 4.days
                cache.evict()
                (maps(installation) / "CTF-BT-Held.unr").shouldExist()
                clock.now = start + 5.days + 1.seconds
                cache.evict()

                (maps(installation) / "CTF-BT-Held.unr").shouldNotExist()
            }

            test("keeps a package fetched before a later one failed, and records its use") {
                val installation = installation("resolve-records-before-failure")
                manifest(installation)
                sourcePackage(installation, "BTPog.u")
                val clock = ManualClock()
                val cache = UtPackageCache(installation, clock = clock)

                shouldThrow<UtPackageException> {
                    cache.resolve(listOf(UtPackage.named("BTPog", ""), UtPackage.named("Missing", "")))
                }
                (system(installation) / "BTPog.u").shouldExist()
                clock.now = start + 3.days + 1.seconds
                cache.evict()

                (system(installation) / "BTPog.u").shouldNotExist()
            }

            test("fetches a package again that evict deleted") {
                val installation = installation("resolve-refetches")
                manifest(installation)
                sourcePackage(installation, "CTF-BT-Test.unr")
                val clock = ManualClock()
                val cache = UtPackageCache(installation, clock = clock)
                cache.resolve(listOf(UtPackage.named("CTF-BT-Test", "")))
                clock.now = start + 4.days
                cache.evict()
                (maps(installation) / "CTF-BT-Test.unr").shouldNotExist()

                cache.resolve(listOf(UtPackage.named("CTF-BT-Test", "")))

                (maps(installation) / "CTF-BT-Test.unr").shouldExist()
            }

            test("never deletes a file in keep, also when it satisfies a resolved package") {
                val installation = installation("resolve-keep")
                manifest(installation)
                val console = file(system(installation) / "UTRecorderConsole.u")
                val clock = ManualClock()
                val cache = UtPackageCache(installation, clock = clock)

                cache.resolve(listOf(UtPackage.named("UTRecorderConsole", "")), keep = listOf(console))
                clock.now = start + 4.days
                cache.resolve(emptyList(), keep = listOf(console))

                console.shouldExist()
            }

            test("runs one resolve at a time across the caches of one installation directory") {
                val installation = installation("resolve-serial")
                manifest(installation)
                sourcePackage(installation, "BTPog.u")
                val readings = Collections.synchronizedList(mutableListOf<String>())
                val released = CountDownLatch(1)
                val entered = CountDownLatch(1)
                val first = UtPackageCache(installation, clock = ManualClock(label = "first", readings = readings))
                val second =
                    UtPackageCache(
                        UtInstallation(installation.directory, installation.config),
                        clock = ManualClock(label = "second", readings = readings),
                    )

                coroutineScope {
                    val firstRun =
                        async(Dispatchers.IO) {
                            first.resolve(listOf(UtPackage.named("BTPog", ""))) {
                                entered.countDown()
                                released.await()
                            }
                        }
                    entered.await()
                    val secondRun = async(Dispatchers.IO) { second.resolve(listOf(UtPackage.named("BTPog", ""))) }
                    delay(300.milliseconds)
                    readings.filter { it == "second" }.shouldBeEmpty()
                    released.countDown()
                    firstRun.await()
                    secondRun.await()
                }

                readings.filter { it == "second" }.shouldNotBeEmpty()
                readings.indexOf("second") shouldBeGreaterThan readings.lastIndexOf("first")
            }

            test("does not throw when a delete fails")
                .config(enabledIf = windowsOnly) {
                    val installation = installation("resolve-delete-fails")
                    manifest(installation)
                    val held = file(maps(installation) / "CTF-BT-Held.unr")
                    val clock = ManualClock()
                    val cache = UtPackageCache(installation, clock = clock)
                    cache.evict()
                    clock.now = start + 4.days

                    FileOutputStream(held.toFile(), true).use { cache.resolve(emptyList()) }

                    held.shouldExist()
                }
        }

        context("evict") {
            test("deletes a package unused for longer than the TTL and keeps one used within it") {
                val installation = installation("evict-expiry")
                manifest(installation)
                sourcePackage(installation, "Old.u")
                sourcePackage(installation, "Recent.u")
                val clock = ManualClock()
                val cache = UtPackageCache(installation, clock = clock)
                cache.resolve(listOf(UtPackage.named("Old", "")))
                clock.now = start + 2.days
                cache.resolve(listOf(UtPackage.named("Recent", "")))
                clock.now = start + 3.days + 1.hours

                cache.evict()

                (system(installation) / "Old.u").shouldNotExist()
                (system(installation) / "Recent.u").shouldExist()
            }

            test("never deletes a core game file, a decompressed base map or a file of another extension") {
                val installation = installation("evict-core")
                manifest(installation, "System/Engine.u", "maps/ctf-face.unr.uz", "Textures/Ancient.utx")
                val files =
                    listOf(
                        file(system(installation) / "Engine.u"),
                        file(maps(installation) / "CTF-Face.unr"),
                        file(installation.directory / UtDirectory.Textures.name / "ANCIENT.utx"),
                        file(system(installation) / "UTRecorderHud.ini"),
                        file(system(installation) / "run.dem"),
                        file(maps(installation) / "Stray.u"),
                    )
                val clock = ManualClock()
                val cache = UtPackageCache(installation, clock = clock)
                cache.evict()
                clock.now = start + 4.days

                cache.evict()

                files.forEach { it.shouldExist() }
            }

            test("deletes nothing without a base manifest") {
                val installation = installation("evict-no-manifest")
                val cached = file(maps(installation) / "CTF-BT-Test.unr")
                val clock = ManualClock()
                val cache = UtPackageCache(installation, clock = clock)
                cache.evict()
                clock.now = start + 4.days

                cache.evict()

                cached.shouldExist()
            }

            test("keeps a package with no recorded use on its first pass and deletes it a TTL later") {
                val installation = installation("evict-unrecorded")
                manifest(installation)
                val cached = file(maps(installation) / "CTF-BT-Test.unr")
                val clock = ManualClock(now = start + 30.days)
                val cache = UtPackageCache(installation, clock = clock)

                cache.evict()
                cached.shouldExist()
                clock.now = start + 33.days + 1.seconds
                cache.evict()

                cached.shouldNotExist()
            }

            test("keeps the last uses across a new cache of the same installation") {
                val installation = installation("evict-persists")
                manifest(installation)
                sourcePackage(installation, "BTPog.u")
                val clock = ManualClock()
                UtPackageCache(installation, clock = clock).resolve(listOf(UtPackage.named("BTPog", "")))
                clock.now = start + 3.days + 1.seconds

                UtPackageCache(UtInstallation(installation.directory, installation.config), clock = clock).evict()

                (system(installation) / "BTPog.u").shouldNotExist()
            }

            test("does not throw when a delete fails, and deletes the package on a later pass")
                .config(enabledIf = windowsOnly) {
                    val installation = installation("evict-delete-fails")
                    manifest(installation)
                    val held = file(maps(installation) / "CTF-BT-Held.unr")
                    val clock = ManualClock()
                    val cache = UtPackageCache(installation, clock = clock)
                    cache.evict()
                    clock.now = start + 4.days

                    FileOutputStream(held.toFile(), true).use { cache.evict() }
                    held.shouldExist()
                    cache.evict()

                    held.shouldNotExist()
                }
        }
    })
