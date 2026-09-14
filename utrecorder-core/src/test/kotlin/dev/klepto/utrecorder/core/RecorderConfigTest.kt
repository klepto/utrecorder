package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.config.PropertySource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RecorderConfigTest :
    FunSpec({
        fun properties(values: Map<String, String>) = PropertySource(properties = values::get, environment = { null })

        test("fromProperties honours every core key") {
            val installation = Path.of("custom-install").toAbsolutePath()
            val properties =
                properties(
                    mapOf(
                        "utrecorder.core.installation_directory" to installation.toString(),
                        "utrecorder.core.startup_timeout" to "PT10S",
                        "utrecorder.core.max_run_margin" to "PT20S",
                        "utrecorder.core.timeout_grace" to "PT30S",
                        "utrecorder.core.exit_delay" to "PT40S",
                    ),
                )

            val config = RecorderConfig.fromProperties(properties, Directories(Path.of("home")))

            config shouldBe
                RecorderConfig(
                    installationDirectory = installation,
                    startupTimeout = 10.seconds,
                    maxRunMargin = 20.seconds,
                    timeoutGrace = 30.seconds,
                    exitDelay = 40.seconds,
                )
        }

        test("fromProperties falls back to ut99 under the installs directory and the default durations") {
            val config = RecorderConfig.fromProperties(properties(emptyMap()), Directories(Path.of("home")))

            config shouldBe
                RecorderConfig(
                    installationDirectory = Path.of("home", "installs", "ut99"),
                    startupTimeout = 90.seconds,
                    maxRunMargin = 120.seconds,
                    timeoutGrace = 60.seconds,
                    exitDelay = 5.seconds,
                )
        }

        test("maxRunTime adds the margin and timeout adds the grace to a known demo duration") {
            val config =
                RecorderConfig(
                    installationDirectory = Path.of("install"),
                    maxRunMargin = 13.seconds,
                    timeoutGrace = 17.seconds,
                )

            config.maxRunTime(10.minutes) shouldBe 613.seconds
            config.timeout(10.minutes) shouldBe 630.seconds
        }
    })
