package dev.klepto.utrecorder.core.capture.audio

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class AudioAlignmentTest :
    FunSpec({
        val track =
            AudioTrack(
                file = Path.of("sound.wav"),
                offset = 2.0,
                length = 10.0,
                demoEnd = 300.75,
            )

        test("place ends the audio on the end of the video when both captures end within the slack of each other") {
            val placement =
                AudioAlignment.place(
                    track = track,
                    videoLength = 8.0,
                    videoCaptureEnd = 300.755,
                    audioOffset = 0.0,
                )

            placement shouldBe AudioAlignment.Placement(offset = 4.0, length = 8.0)
        }

        test("place keeps the track's own placement when the captures end farther apart than the slack") {
            val placement =
                AudioAlignment.place(
                    track = track,
                    videoLength = 8.0,
                    videoCaptureEnd = 301.0,
                    audioOffset = 0.0,
                )

            placement shouldBe AudioAlignment.Placement(offset = 2.0, length = 10.0)
        }

        test("place keeps the track's own placement when the video capture end is not known") {
            val placement =
                AudioAlignment.place(
                    track = track,
                    videoLength = 8.0,
                    videoCaptureEnd = null,
                    audioOffset = 0.0,
                )

            placement shouldBe AudioAlignment.Placement(offset = 2.0, length = 10.0)
        }

        test("place keeps the track's own placement when the demo end of the track is not known") {
            val placement =
                AudioAlignment.place(
                    track = track.copy(demoEnd = 0.0),
                    videoLength = 8.0,
                    videoCaptureEnd = 300.75,
                    audioOffset = 0.0,
                )

            placement shouldBe AudioAlignment.Placement(offset = 2.0, length = 10.0)
        }

        test("place keeps the track's own placement when the shifted start would fall before the start of the wav") {
            val placement =
                AudioAlignment.place(
                    track = track.copy(offset = 0.5),
                    videoLength = 13.0,
                    videoCaptureEnd = 300.75,
                    audioOffset = 0.0,
                )

            placement shouldBe AudioAlignment.Placement(offset = 0.5, length = 10.0)
        }

        test("place adds the audio offset to the start of the placed audio") {
            val placement =
                AudioAlignment.place(
                    track = track,
                    videoLength = 8.0,
                    videoCaptureEnd = 300.755,
                    audioOffset = 0.5,
                )

            placement shouldBe AudioAlignment.Placement(offset = 4.5, length = 8.0)
        }
    })
