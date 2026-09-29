package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.SystemConfigReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RecorderConfigTest :
    FunSpec({
        test("reads every record key") {
            val properties =
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
                    "utrecorder.record.frame_timeout" to "PT20M",
                    "utrecorder.record.arguments" to "-tune zerolatency",
                    "utrecorder.record.audio_bitrate" to "256k",
                    "utrecorder.record.audio_filter" to "highpass=f=80",
                    "utrecorder.record.audio_offset" to "0.25",
                    "utrecorder.record.sound_render" to "false",
                    "utrecorder.record.sound_width" to "32",
                    "utrecorder.record.sound_height" to "48",
                    "utrecorder.record.sound_fps" to "30",
                    "utrecorder.record.linux_mesa_threads" to "4",
                    "utrecorder.record.linux_mesa_vector_width" to "128",
                    "utrecorder.record.linux_mesa_glthread" to "true",
                    "utrecorder.record.linux_mesa_no_error" to "true",
                )
            val reader = SystemConfigReader(properties = properties::get, environment = { null })

            val config = RecorderConfig(reader)

            with(config.video) {
                fps shouldBe 90
                codec shouldBe "libx264"
                preset shouldBe "7"
                crf shouldBe "35"
                bitrate shouldBe "8192k"
                svtav1Params shouldBe "tune=1"
                gop shouldBe 240
                width shouldBe 1920
                height shouldBe 1080
                queue shouldBe 16
                port shouldBe 12345
                timeout shouldBe 20.minutes
                arguments shouldBe "-tune zerolatency"
            }
            with(config.video.linuxMesa) {
                threads shouldBe 4
                vectorWidth shouldBe "128"
                glthread shouldBe true
                noError shouldBe true
            }
            with(config.audio) {
                bitrate shouldBe "256k"
                filter shouldBe "highpass=f=80"
                offset shouldBe 0.25
                render shouldBe false
                width shouldBe 32
                height shouldBe 48
                fps shouldBe 30
            }
        }

        test("falls back to the literal defaults") {
            val config = RecorderConfig(ConfigReader.EMPTY)

            with(config.video) {
                fps shouldBe 60
                codec shouldBe "libsvtav1"
                preset shouldBe "3"
                crf shouldBe "28"
                bitrate shouldBe "4800k"
                svtav1Params shouldBe "tune=0"
                gop shouldBe 300
                width shouldBe 1280
                height shouldBe 720
                queue shouldBe 32
                port shouldBe 0
                timeout shouldBe 1800.seconds
                arguments shouldBe ""
            }
            with(config.video.linuxMesa) {
                threads shouldBe 0
                vectorWidth shouldBe ""
                glthread shouldBe false
                noError shouldBe false
            }
            with(config.audio) {
                bitrate shouldBe "192k"
                filter shouldBe "aresample=async=1,loudnorm=I=-34:TP=-1.5:LRA=11"
                offset shouldBe 0.0
                render shouldBe true
                width shouldBe 64
                height shouldBe 64
                fps shouldBe 60
            }
        }
    })
