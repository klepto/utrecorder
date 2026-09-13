package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [RecorderConfig.fromProperties], the timeout derivations, and the defaults they fall back to.
 */
class RecorderConfigTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-recorderconfig-test")
        val directories = Directories(home)
        val property = SystemProperties()

        afterSpec { home.toFile().deleteRecursively() }
        afterTest { property.clear() }

        test("defaults are StartupTimeout 90s, maxRunMargin 120s, timeoutGrace 60s, exitDelay 5s") {
            val config = RecorderConfig(installationDirectory = directories.installs / "ut99")

            config.startupTimeout shouldBe 90.seconds
            config.maxRunMargin shouldBe 120.seconds
            config.timeoutGrace shouldBe 60.seconds
            config.exitDelay shouldBe 5.seconds
        }

        test("maxRunSeconds returns duration + maxRunMargin for a known demo duration") {
            val config = RecorderConfig(installationDirectory = directories.installs / "ut99")
            val demoDuration = 10.minutes

            config.maxRunSeconds(demoDuration) shouldBe (demoDuration + 120.seconds)
        }

        test("timeout returns duration + maxRunMargin + timeoutGrace for a known demo duration") {
            val config = RecorderConfig(installationDirectory = directories.installs / "ut99")
            val demoDuration = 10.minutes

            config.timeout(demoDuration) shouldBe (demoDuration + 120.seconds + 60.seconds)
        }

        test("fromProperties honours every core.* override") {
            val installation = home / "custom-install"
            property("utrecorder.core.installation_directory", installation.toString())
            property("utrecorder.core.startup_timeout", "PT10S")
            property("utrecorder.core.max_run_margin", "PT20S")
            property("utrecorder.core.timeout_grace", "PT30S")
            property("utrecorder.core.exit_delay", "PT40S")

            val config = RecorderConfig.fromProperties(directories = directories)

            config.installationDirectory shouldBe installation.toAbsolutePath()
            config.startupTimeout shouldBe 10.seconds
            config.maxRunMargin shouldBe 20.seconds
            config.timeoutGrace shouldBe 30.seconds
            config.exitDelay shouldBe 40.seconds
        }

        test("fromProperties falls back to defaults when no keys are set") {
            val config = RecorderConfig.fromProperties(directories = directories)

            config.installationDirectory shouldBe directories.installs / "ut99"
            config.startupTimeout shouldBe 90.seconds
            config.maxRunMargin shouldBe 120.seconds
            config.timeoutGrace shouldBe 60.seconds
            config.exitDelay shouldBe 5.seconds
        }
    })
