package dev.klepto.utrecorder.core.media

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.util.Locale

class FfmpegMuxerTest :
    FunSpec({
        test("arguments copy the video, cut and encode the audio as given, and name every file by its absolute path") {
            val muxer = FfmpegMuxer(filter = "aresample=async=1", bitrate = "160k")
            val directory = Path.of("").toAbsolutePath()

            muxer.arguments(Path.of("video.mp4"), Path.of("sound.wav"), 2.0, 10.0, Path.of("out.mp4")) shouldBe
                listOf(
                    "-y",
                    "-i",
                    directory.resolve("video.mp4").toString(),
                    "-ss",
                    "2.000",
                    "-t",
                    "10.000",
                    "-i",
                    directory.resolve("sound.wav").toString(),
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
                    directory.resolve("out.mp4").toString(),
                )
        }

        test("arguments write the audio offset and length with a decimal point in a comma-decimal locale") {
            val muxer = FfmpegMuxer(filter = "aresample=async=1", bitrate = "160k")
            val locale = Locale.getDefault()
            Locale.setDefault(Locale.GERMANY)
            try {
                val arguments = muxer.arguments(Path.of("video.mp4"), Path.of("sound.wav"), 1.234, 5.5, Path.of("out.mp4"))

                arguments.subList(3, 7) shouldBe listOf("-ss", "1.234", "-t", "5.500")
            } finally {
                Locale.setDefault(locale)
            }
        }
    })
