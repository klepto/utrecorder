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
        test("parsing a property source that sets the record keys produces a config carrying them typed") {
            val properties =
                properties(
                    mapOf(
                        "utrecorder.record.fps" to "90",
                        "utrecorder.record.codec" to "libx264",
                        "utrecorder.record.preset" to "7",
                        "utrecorder.record.crf" to "35",
                        "utrecorder.record.bitrate" to "8192k",
                        "utrecorder.record.svtav1_params" to "tune=1",
                        "utrecorder.record.gop" to "240",
                        "utrecorder.record.width" to "1920",
                        "utrecorder.record.height" to "1080",
                        "utrecorder.record.frame_queue" to "16",
                        "utrecorder.record.frame_port" to "12345",
                        "utrecorder.record.arguments" to "-tune zerolatency",
                        "utrecorder.record.audio" to "false",
                        "utrecorder.record.audio_bitrate" to "256k",
                        "utrecorder.record.audio_filter" to "highpass=f=80",
                        "utrecorder.record.audio_offset" to "0.25",
                        "utrecorder.record.sound_render" to "false",
                        "utrecorder.record.sound_width" to "32",
                        "utrecorder.record.sound_height" to "48",
                        "utrecorder.record.sound_fps" to "30",
                        "utrecorder.record.render_threads" to "4",
                        "utrecorder.record.sound_render_threads" to "2",
                        "utrecorder.record.render_vector_width" to "128",
                        "utrecorder.record.render_glthread" to "true",
                        "utrecorder.record.render_no_error" to "true",
                    ),
                )

            val config = RecorderConfig.fromProperties(properties, Directories(Path.of("home")))

            config shouldBe
                RecorderConfig(
                    installationDirectory = Path.of("home", "installs", "ut99"),
                    fps = 90,
                    codec = "libx264",
                    preset = "7",
                    crf = "35",
                    bitrate = "8192k",
                    svtav1Params = "tune=1",
                    gop = 240,
                    width = 1920,
                    height = 1080,
                    frameQueue = 16,
                    framePort = 12345,
                    recordArguments = "-tune zerolatency",
                    audio = false,
                    audioBitrate = "256k",
                    audioFilter = "highpass=f=80",
                    audioOffset = 0.25,
                    soundRender = false,
                    soundWidth = 32,
                    soundHeight = 48,
                    soundFps = 30,
                    renderThreads = 4,
                    soundRenderThreads = 2,
                    renderVectorWidth = "128",
                    renderGlthread = true,
                    renderNoError = true,
                )
        }
        test("parsing a property source that sets none produces the literal defaults") {
            val config = RecorderConfig.fromProperties(properties(emptyMap()), Directories(Path.of("home")))

            config shouldBe
                RecorderConfig(
                    installationDirectory = Path.of("home", "installs", "ut99"),
                    fps = 60,
                    codec = "libsvtav1",
                    preset = "4",
                    crf = "28",
                    bitrate = "4800k",
                    svtav1Params = "tune=0",
                    gop = 300,
                    width = 1280,
                    height = 720,
                    frameQueue = 32,
                    audio = true,
                    audioBitrate = "192k",
                    audioFilter = "aresample=async=1,loudnorm=I=-34:TP=-1.5:LRA=11",
                    audioOffset = 0.0,
                    soundRender = true,
                    soundWidth = 64,
                    soundHeight = 64,
                    soundFps = 60,
                    soundRenderThreads = 1,
                    frameTimeout = 1800.seconds,
                )
        }

        test("parsing a duration record key with its unit produces the frame timeout") {
            val properties = properties(mapOf("utrecorder.record.frame_timeout" to "PT20M"))

            val config = RecorderConfig.fromProperties(properties, Directories(Path.of("home")))

            config.frameTimeout shouldBe 20.minutes
        }
    })
