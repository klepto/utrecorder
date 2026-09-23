package dev.klepto.utrecorder.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class MuxerTest :
    FunSpec({
        val video = Path.of("capture.mp4")
        val target = Path.of("out.mp4")

        fun track() =
            SoundTrack(
                file = Path.of("sound.wav"),
                offset = 2.0,
                length = 10.0,
                demoEnd = 300.75,
                demoSpan = 288.25,
            )

        fun config() = RecorderConfig(Path.of("install"))

        fun muxer() = Muxer(config())

        fun arguments() = muxer().arguments(video, track(), target)

        test("the arguments map the video input's stream and the audio input's, seeked and cut at the placement") {
            arguments() shouldBe
                listOf(
                    "-y",
                    "-i",
                    "capture.mp4",
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
                    config().audioFilter,
                    "-c:a",
                    "aac",
                    "-b:a",
                    config().audioBitrate,
                    "-ac",
                    "2",
                    "-shortest",
                    "-movflags",
                    "+faststart",
                    "out.mp4",
                )
        }

        test("the audio filter argument carries the configured filter verbatim") {
            val filter = "aresample=async=1"
            val custom = Muxer(config().copy(audioFilter = filter))

            custom.arguments(video, track(), target) shouldContainInOrder listOf("-af", filter)
        }

        test("the video input carries no trim flags beside the audio's seek and cut") {
            val arguments = arguments()
            val videoInputEnd = arguments.indexOf("sound.wav")

            arguments.subList(0, videoInputEnd).shouldNotContainAnyOf(listOf("-c:v", "-af", "-c:a", "-ac", "-shortest"))
        }
    })

private infix fun List<String>.shouldContainInOrder(needle: List<String>) {
    val window = indexOf(needle.first())
    if (window < 0) throw AssertionError("missing $needle")
    subList(window, window + needle.size) shouldBe needle
}

private fun List<String>.shouldNotContainAnyOf(needles: List<String>) {
    needles.forEach { needle ->
        if (contains(needle)) throw AssertionError("unexpected $needle")
    }
}
