package dev.klepto.utrecorder.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class SoundPlacementTest :
    FunSpec({
        fun track() =
            SoundTrack(
                file = Path.of("sound.wav"),
                offset = 2.0,
                length = 10.0,
                demoEnd = 300.75,
                demoSpan = 288.25,
            )

        fun trackWithNoDemoEnd() = track().copy(demoEnd = 0.0)

        fun defaultConfig() = RecorderConfig(Path.of("install"))

        test("anchoring within the slack seeks to the end of the demo's audio against the video length, taking it") {
            val placement =
                SoundPlacement.place(
                    track = track(),
                    videoLength = 8.0,
                    framePassEnd = 300.755,
                    config = defaultConfig(),
                )

            placement shouldBe SoundPlacement.Placement(offset = 4.0, length = 8.0)
        }

        test("the passes ending on demo clocks farther apart than the end slack keeps the track's own placement") {
            val placement =
                SoundPlacement.place(
                    track = track(),
                    videoLength = 8.0,
                    framePassEnd = 301.0,
                    config = defaultConfig(),
                )

            placement shouldBe SoundPlacement.Placement(offset = 2.0, length = 10.0)
        }

        test("a frame pass end clock of none keeps the track's own placement") {
            val placement =
                SoundPlacement.place(
                    track = track(),
                    videoLength = 8.0,
                    framePassEnd = null,
                    config = defaultConfig(),
                )

            placement shouldBe SoundPlacement.Placement(offset = 2.0, length = 10.0)
        }

        test("a sound track with no demo clock keeps its own placement") {
            val placement =
                SoundPlacement.place(
                    track = trackWithNoDemoEnd(),
                    videoLength = 8.0,
                    framePassEnd = 300.75,
                    config = defaultConfig(),
                )

            placement shouldBe SoundPlacement.Placement(offset = 2.0, length = 10.0)
        }

        test("an anchored seek that would begin before the wav keeps the track's own placement") {
            val placement =
                SoundPlacement.place(
                    track = track().copy(offset = 0.5),
                    videoLength = 13.0,
                    framePassEnd = 300.75,
                    config = defaultConfig(),
                )

            placement shouldBe SoundPlacement.Placement(offset = 0.5, length = 10.0)
        }

        test("the audio nudge adds into the anchored seek") {
            val placement =
                SoundPlacement.place(
                    track = track(),
                    videoLength = 8.0,
                    framePassEnd = 300.755,
                    config = RecorderConfig(Path.of("install"), audioOffset = 0.5),
                )

            placement shouldBe SoundPlacement.Placement(offset = 4.5, length = 8.0)
        }
    })
