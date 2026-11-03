package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.SystemConfigReader
import dev.klepto.utrecorder.ut99.UtPlatform.LINUX
import dev.klepto.utrecorder.ut99.UtPlatform.WINDOWS
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class UtConfigTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-utconfig-test")
        val directories = DirectoriesConfig(ConfigReader.EMPTY, home = home)

        afterSpec { home.toFile().deleteRecursively() }

        fun readerOf(vararg properties: Pair<String, String>): ConfigReader =
            SystemConfigReader(properties = mapOf(*properties)::get, environment = { null })

        test("installs the game in the ut99 directory under the installs directory") {
            UtConfig(ConfigReader.EMPTY, directories).gameDirectory shouldBe directories.installs / "ut99"
        }

        test("reads game_directory as an absolute path") {
            val reader = readerOf("utrecorder.game_directory" to "custom-install")

            UtConfig(reader, directories).gameDirectory shouldBe Path.of("custom-install").toAbsolutePath()
        }

        test("reads iso_url and iso_md5") {
            val reader =
                readerOf(
                    "utrecorder.iso_url" to "https://example.test/ut.iso",
                    "utrecorder.iso_md5" to "deadbeef",
                )

            val config = UtConfig(reader, directories)
            config.isoUrl shouldBe "https://example.test/ut.iso"
            config.isoMd5 shouldBe "deadbeef"
        }

        test("reads patch_url and patch_version") {
            val reader =
                readerOf(
                    "utrecorder.patch_url" to "https://example.test/patches",
                    "utrecorder.patch_version" to "999z",
                )

            val config = UtConfig(reader, directories)
            config.patchUrl shouldBe "https://example.test/patches"
            config.patchVersion shouldBe "999z"
        }

        test("patchFileName names the OldUnreal 469e release file for the current platform") {
            val config = UtConfig(ConfigReader.EMPTY, directories)
            val platformSuffix =
                when (UtPlatform.current()) {
                    WINDOWS -> "Windows-x86.zip"
                    LINUX -> "Linux-x86.tar.bz2"
                }

            config.patchFileName shouldBe "OldUnreal-UTPatch469e-$platformSuffix"
        }

        test("reads redirect_urls as a comma-separated list of trimmed URLs") {
            val reader = readerOf("utrecorder.redirect_urls" to " http://one.test , http://two.test ")

            val config = UtConfig(reader, directories)
            config.redirectUrls shouldBe listOf("http://one.test", "http://two.test")
        }

        test("keeps the two default redirect URLs when redirect_urls is not set") {
            val config = UtConfig(ConfigReader.EMPTY, directories)

            config.redirectUrls shouldBe listOf("http://api.utbt.net/redirect", "http://uz.ut-files.com")
        }

        test("reads decompress_timeout as a duration") {
            val reader = readerOf("utrecorder.decompress_timeout" to "90s")

            UtConfig(reader, directories).decompressTimeout shouldBe 90.seconds
        }

        test("gives a decompression five minutes when decompress_timeout is not set") {
            UtConfig(ConfigReader.EMPTY, directories).decompressTimeout shouldBe 5.minutes
        }

        test("keeps a package for 3 days when package_ttl is not set") {
            UtConfig(ConfigReader.EMPTY, directories).packageTtl shouldBe 3.days
        }

        test("reads package_ttl as a duration") {
            val reader = readerOf("utrecorder.package_ttl" to "72h")

            UtConfig(reader, directories).packageTtl shouldBe 72.hours
        }

        test("accepts a package_ttl of 1 hour") {
            UtConfig(ConfigReader.EMPTY, directories, packageTtl = 1.hours).packageTtl shouldBe 1.hours
        }

        test("throws when package_ttl is less than 1 hour") {
            val reader = readerOf("utrecorder.package_ttl" to "59m")

            shouldThrow<IllegalArgumentException> { UtConfig(reader, directories) }
        }
    })
