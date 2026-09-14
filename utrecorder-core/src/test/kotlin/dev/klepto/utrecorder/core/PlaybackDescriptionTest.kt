package dev.klepto.utrecorder.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PlaybackDescriptionTest :
    FunSpec({
        fun config() =
            RecorderConfig(
                installationDirectory = Path.of("install"),
                startupTimeout = 11.seconds,
                maxRunMargin = 13.seconds,
                exitDelay = 7.seconds,
            )

        test("of plays the staged demo and takes its run values from the config") {
            val description = PlaybackDescription.of("ut-2026-09-13-1030-05", 10.minutes, config())

            description shouldBe
                PlaybackDescription(
                    stagedDemoName = "ut-2026-09-13-1030-05",
                    execLine = "demoplay ut-2026-09-13-1030-05",
                    console = "UTRecorder.UTRecorderConsole",
                    exitDelay = 7.seconds,
                    startupTimeout = 11.seconds,
                    maxRunTime = 613.seconds,
                )
        }

        test("recorderIni renders the run values in whole seconds") {
            val description = PlaybackDescription.of("ut-2026-09-13-1030-05", 10.minutes, config())

            description.recorderIni().toString() shouldBe
                """
                [UTRecorder.UTRecorderConsole]
                bAutoExit=True
                bStopDemoFirst=True
                ExitDelay=7
                StartupTimeout=11
                MaxRunSeconds=613

                """.trimIndent()
        }
    })
