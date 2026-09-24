package dev.klepto.utrecorder.core.media

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class FfmpegMuxerTest :
    FunSpec({
        test("arguments copy the video and cut the audio at its offset and length with the given filter and bitrate") {
            val muxer = FfmpegMuxer(filter = "aresample=async=1", bitrate = "160k")

            muxer.arguments(Path.of("video.mp4"), Path.of("sound.wav"), 2.0, 10.0, Path.of("out.mp4")) shouldBe
                listOf(
                    "-y",
                    "-i",
                    "video.mp4",
                    "-ss",
                    "2.000",
                    "-t",
                    "10.000",
                    "-i",
                    "sound.wav",
                    "-map",
                    "0:v:0",
                    "-map",
                    "1:a:0",
                    "-c:v",
                    "copy",
                    "-af",
                    "aresample=async=1",
                    "-c:a",
                    "aac",
                    "-b:a",
                    "160k",
                    "-ac",
                    "2",
                    "-shortest",
                    "-movflags",
                    "+faststart",
                    "out.mp4",
                )
        }
    })
