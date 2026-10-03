package dev.klepto.utrecorder.core.capture.video

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.SystemConfigReader
import dev.klepto.utrecorder.core.demo.DemoPlaybackRenderDevice
import dev.klepto.utrecorder.core.demo.DemoPlaybackViewport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.seconds

class VideoCaptureConfigTest :
    FunSpec({
        fun reader(vararg keys: Pair<String, String>): ConfigReader =
            SystemConfigReader(properties = mapOf(*keys)::get, environment = { null })

        test("viewport renders at the resolution of the format with XOpenGL and no frame rate limit") {
            val format = VideoFormat(width = 960, height = 540, bitrate = 4_800_000, preset = "4", fitsBudget = true)
            VideoCaptureConfig(ConfigReader.EMPTY).viewport(format) shouldBe
                DemoPlaybackViewport(
                    width = 960,
                    height = 540,
                    frameRateLimit = 0.0,
                    renderDevice = DemoPlaybackRenderDevice.XOPENGL,
                )
        }

        test("renderFps renders the motion blur samples of every encoded frame") {
            VideoCaptureConfig(ConfigReader.EMPTY, fps = 60, motionBlurSamples = 4).renderFps() shouldBe 240
        }

        test("renderFps renders one frame per encoded frame when motion blur is off") {
            VideoCaptureConfig(ConfigReader.EMPTY, fps = 60, motionBlur = false).renderFps() shouldBe 60
        }

        test("format takes 1080p at its maximum bitrate for a 10 s run") {
            VideoCaptureConfig(ConfigReader.EMPTY).format(10.seconds, "96k") shouldBe
                VideoFormat(width = 1920, height = 1080, bitrate = 10_000_000, preset = "6", fitsBudget = true)
        }

        test("format takes 1080p at the budget for a 70 s run") {
            VideoCaptureConfig(ConfigReader.EMPTY).format(70.seconds, "96k") shouldBe
                VideoFormat(width = 1920, height = 1080, bitrate = 8_348_000, preset = "6", fitsBudget = true)
        }

        test("format takes 900p for a 90 s run") {
            VideoCaptureConfig(ConfigReader.EMPTY).format(90.seconds, "96k") shouldBe
                VideoFormat(width = 1600, height = 900, bitrate = 6_512_000, preset = "6", fitsBudget = true)
        }

        test("format takes 720p at preset 4 for a 150 s run") {
            VideoCaptureConfig(ConfigReader.EMPTY).format(150.seconds, "96k") shouldBe
                VideoFormat(width = 1280, height = 720, bitrate = 3_904_000, preset = "4", fitsBudget = true)
        }

        test("format falls back to the smallest tier at its maximum bitrate for a 300 s run") {
            VideoCaptureConfig(ConfigReader.EMPTY).format(300.seconds, "96k") shouldBe
                VideoFormat(width = 1280, height = 720, bitrate = 6_000_000, preset = "4", fitsBudget = false)
        }

        test("format falls back when the target size is 0") {
            VideoCaptureConfig(reader("utrecorder.record.target_size" to "0")).format(10.seconds, "96k") shouldBe
                VideoFormat(width = 1280, height = 720, bitrate = 6_000_000, preset = "4", fitsBudget = false)
        }

        test("format takes the larger tier for a run on a tier boundary") {
            VideoCaptureConfig(reader("utrecorder.record.target_size" to "10120000")).format(8.seconds, "96k") shouldBe
                VideoFormat(width = 1920, height = 1080, bitrate = 8_000_000, preset = "6", fitsBudget = true)
        }

        test("tiers orders the ladder from the highest minimum bitrate whatever order record.tiers lists it in") {
            val config =
                VideoCaptureConfig(
                    reader(
                        "utrecorder.record.tiers" to
                            "1280x720:2700k-6000k:4,1920x1080:8000k-10000k:6,1600x900:6000k-8000k:6",
                    ),
                )

            config.tiers shouldBe
                listOf(
                    VideoTier(1920, 1080, 8_000_000, 10_000_000, "6"),
                    VideoTier(1600, 900, 6_000_000, 8_000_000, "6"),
                    VideoTier(1280, 720, 2_700_000, 6_000_000, "4"),
                )
            config.format(90.seconds, "96k") shouldBe
                VideoFormat(width = 1600, height = 900, bitrate = 6_512_000, preset = "6", fitsBudget = true)
        }

        test("tiers parses an entry without a preset field with no preset") {
            VideoCaptureConfig(reader("utrecorder.record.tiers" to "1280x720:2700k-6000k")).tiers shouldBe
                listOf(VideoTier(1280, 720, 2_700_000, 6_000_000, null))
        }

        test("record.tiers without a tier fails naming the key") {
            shouldThrow<IllegalArgumentException> {
                VideoCaptureConfig(reader("utrecorder.record.tiers" to " , "))
            }.message shouldContain "record.tiers"
        }

        listOf(
            "1280x720:2700k",
            "1281x720:2700k-6000k:4",
            "1280x721:2700k-6000k:4",
            "1280x720:6000k-2700k:4",
        ).forEach { entry ->
            test("record.tiers entry $entry fails naming the key and the entry") {
                val message =
                    shouldThrow<IllegalArgumentException> {
                        VideoCaptureConfig(reader("utrecorder.record.tiers" to "1920x1080:8000k-10000k:6,$entry"))
                    }.message

                message shouldContain "record.tiers"
                message shouldContain entry
            }
        }
    })
