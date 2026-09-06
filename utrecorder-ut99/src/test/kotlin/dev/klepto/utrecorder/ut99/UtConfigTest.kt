package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.config.Directories
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div

/**
 * Tests for [UtConfig.fromProperties] and the directories and system properties it reads.
 */
class UtConfigTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-utconfig-test")
        val directories = Directories(home)
        val property = SystemProperties()

        afterSpec { home.toFile().deleteRecursively() }
        afterTest { property.clear() }

        test("fromProperties derives gameDirectory from the installs directory of the given directories") {
            UtConfig.fromProperties(directories = directories).gameDirectory shouldBe directories.installs / "ut99"
        }

        test("fromProperties derives packagesDirectory and cacheDirectory from the given directories") {
            val config = UtConfig.fromProperties(directories = directories)

            config.packagesDirectory shouldBe directories.packages
            config.cacheDirectory shouldBe directories.cache
        }

        test("fromProperties honours iso_url and iso_md5") {
            property("utrecorder.iso_url", "https://example.test/ut.iso")
            property("utrecorder.iso_md5", "deadbeef")

            val config = UtConfig.fromProperties(directories = directories)
            config.isoUrl shouldBe "https://example.test/ut.iso"
            config.isoMd5 shouldBe "deadbeef"
        }

        test("fromProperties honours patch_url and patch_version") {
            property("utrecorder.patch_url", "https://example.test/patches")
            property("utrecorder.patch_version", "999z")

            val config = UtConfig.fromProperties(directories = directories)
            config.patchUrl shouldBe "https://example.test/patches"
            config.patchVersion shouldBe "999z"
        }

        test("patchFileName follows OldUnreal's naming convention for the current platform") {
            val config = UtConfig.fromProperties(directories = directories)
            val platformSuffix = if (UT_PLATFORM == UtPlatform.WINDOWS) "Windows-x86.zip" else "Linux-x86.tar.bz2"

            config.patchFileName shouldBe "OldUnreal-UTPatch${config.patchVersion}-$platformSuffix"
        }
    })
