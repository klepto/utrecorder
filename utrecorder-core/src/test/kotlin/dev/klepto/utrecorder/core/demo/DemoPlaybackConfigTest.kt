package dev.klepto.utrecorder.core.demo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DemoPlaybackConfigTest :
    FunSpec({
        test("of plays the demo through the recorder console and adds the run margin and the timeout grace to the demo duration") {
            DemoPlaybackConfig.of("ut-2026-09-13-1030-05", 10.minutes) shouldBe
                DemoPlaybackConfig(
                    demoFileName = "ut-2026-09-13-1030-05",
                    demoDuration = 10.minutes,
                    packages = emptyList(),
                    runStart = null,
                    runStop = null,
                    execLine = "demoplay ut-2026-09-13-1030-05",
                    console = "UTRecorder.UTRecorderConsole",
                    exitDelay = Duration.ZERO,
                    startupTimeout = 90.seconds,
                    maxRunTime = 720.seconds,
                    timeout = 780.seconds,
                )
        }

        test("gameIniValues installs the recorder console, auto-exits and truncates the run values to whole seconds") {
            val description =
                DemoPlaybackConfig(
                    demoFileName = "ut-2026-09-13-1030-05",
                    demoDuration = 10.minutes,
                    packages = emptyList(),
                    runStart = null,
                    runStop = null,
                    execLine = "demoplay ut-2026-09-13-1030-05",
                    console = "UTRecorder.UTRecorderConsole",
                    exitDelay = 7900.milliseconds,
                    startupTimeout = 11.seconds,
                    maxRunTime = 613.seconds,
                    timeout = 630.seconds,
                )

            description.gameIniValues() shouldBe
                mapOf(
                    "Engine.Engine" to mapOf("Console" to "UTRecorder.UTRecorderConsole"),
                    "UTRecorder.UTRecorderConsole" to
                        mapOf(
                            "bAutoExit" to "True",
                            "bStopDemoFirst" to "True",
                            "ExitDelay" to "7",
                            "StartupTimeout" to "11",
                            "MaxRunSeconds" to "613",
                        ),
                )
        }

        test("runDuration spans the run from its start to its stop") {
            val description = DemoPlaybackConfig.of("demo", 190.seconds, runStart = 166.seconds, runStop = 189.seconds)

            description.runDuration() shouldBe 23.seconds
        }

        test("runDuration falls back to the start and the end of the demo") {
            DemoPlaybackConfig.of("demo", 190.seconds).runDuration() shouldBe 190.seconds
            DemoPlaybackConfig.of("demo", 190.seconds, runStart = 166.seconds).runDuration() shouldBe 24.seconds
            DemoPlaybackConfig.of("demo", 190.seconds, runStop = 22.seconds).runDuration() shouldBe 22.seconds
        }
    })
