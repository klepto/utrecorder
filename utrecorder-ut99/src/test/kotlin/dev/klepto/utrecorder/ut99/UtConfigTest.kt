package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.config.DirectoriesConfig
import dev.klepto.utrecorder.common.config.SystemConfigReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div

class UtConfigTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-utconfig-test")
        val directories = DirectoriesConfig(home)

        afterSpec { home.toFile().deleteRecursively() }

        fun properties(vararg properties: Pair<String, String>) =
            SystemConfigReader(properties = mapOf(*properties)::get, environment = { null })

        test("fromProperties derives gameDirectory from the installs directory of the given directories") {
            UtConfig.fromProperties(properties(), directories).gameDirectory shouldBe directories.installs / "ut99"
        }

        test("fromProperties honours iso_url and iso_md5") {
            val reader =
                properties(
                    "utrecorder.iso_url" to "https://example.test/ut.iso",
                    "utrecorder.iso_md5" to "deadbeef",
                )

            val config = UtConfig.fromProperties(reader, directories)
            config.isoUrl shouldBe "https://example.test/ut.iso"
            config.isoMd5 shouldBe "deadbeef"
        }

        test("fromProperties honours patch_url and patch_version") {
            val reader =
                properties(
                    "utrecorder.patch_url" to "https://example.test/patches",
                    "utrecorder.patch_version" to "999z",
                )

            val config = UtConfig.fromProperties(reader, directories)
            config.patchUrl shouldBe "https://example.test/patches"
            config.patchVersion shouldBe "999z"
        }

        test("patchFileName follows OldUnreal's naming convention for the current platform") {
            val config = UtConfig.fromProperties(properties(), directories)
            val platformSuffix =
                when (UtPlatform.current()) {
                    UtPlatform.WINDOWS -> "Windows-x86.zip"
                    UtPlatform.LINUX -> "Linux-x86.tar.bz2"
                }

            config.patchFileName shouldBe "OldUnreal-UTPatch469e-$platformSuffix"
        }

        test("fromProperties reads redirect_urls as a comma-separated list") {
            val reader = properties("utrecorder.redirect_urls" to " http://one.test , http://two.test ")

            val config = UtConfig.fromProperties(reader, directories)
            config.redirectUrls shouldBe listOf("http://one.test", "http://two.test")
        }

        test("redirectUrls defaults to the two redirects when redirect_urls is unset") {
            val config = UtConfig.fromProperties(properties(), directories)

            config.redirectUrls shouldBe listOf("http://api.utbt.net/redirect", "http://uz.ut-files.com")
        }
    })
