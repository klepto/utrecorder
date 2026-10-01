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
                demoStart = 290.75,
            )

        test("place starts the audio where the wav plays the demo time the video starts at") {
            val placement =
                AudioAlignment.place(
                    track = track,
                    videoLength = 8.0,
                    videoStart = 293.0,
                    audioOffset = 0.0,
                )

            placement shouldBe AudioAlignment.Placement(offset = 4.25, length = 8.0)
        }

        test("place runs the placed audio for the length of the video when the video runs past the end of the track") {
            val placement =
                AudioAlignment.place(
                    track = track,
                    videoLength = 8.05,
                    videoStart = 292.75,
                    audioOffset = 0.0,
                )

            placement shouldBe AudioAlignment.Placement(offset = 4.0, length = 8.05)
        }

        test("place keeps the track's own placement when the video start is not known") {
            val placement =
                AudioAlignment.place(
                    track = track,
                    videoLength = 8.0,
                    videoStart = null,
                    audioOffset = 0.0,
                )

            placement shouldBe AudioAlignment.Placement(offset = 2.0, length = 10.0)
        }

        test("place keeps the track's own placement when the shifted start would fall before the start of the wav") {
            val placement =
                AudioAlignment.place(
                    track = track.copy(offset = 0.5),
                    videoLength = 11.0,
                    videoStart = 290.0,
                    audioOffset = 0.0,
                )

            placement shouldBe AudioAlignment.Placement(offset = 0.5, length = 10.0)
        }

        test("place adds the audio offset to the start of the placed audio") {
            val placement =
                AudioAlignment.place(
                    track = track,
                    videoLength = 8.0,
                    videoStart = 292.75,
                    audioOffset = 0.5,
                )

            placement shouldBe AudioAlignment.Placement(offset = 4.5, length = 8.0)
        }
    })
