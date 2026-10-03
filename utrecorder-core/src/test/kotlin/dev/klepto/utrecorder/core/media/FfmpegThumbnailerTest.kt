package dev.klepto.utrecorder.core.media

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.util.Locale

class FfmpegThumbnailerTest :
    FunSpec({
        test("arguments write one frame at the given time as a JPEG, and name every file by its absolute path") {
            val directory = Path.of("").toAbsolutePath()

            FfmpegThumbnailer().arguments(Path.of("cap.mp4"), 1.0, Path.of("cap.jpg")) shouldBe
                listOf(
                    "-y",
                    "-ss",
                    "1.000",
                    "-i",
                    directory.resolve("cap.mp4").toString(),
                    "-frames:v",
                    "1",
                    "-q:v",
                    "2",
                    "-update",
                    "1",
                    directory.resolve("cap.jpg").toString(),
                )
        }

        test("arguments write the time with a decimal point in a comma-decimal locale") {
            val locale = Locale.getDefault()
            Locale.setDefault(Locale.GERMANY)
            try {
                val arguments = FfmpegThumbnailer().arguments(Path.of("cap.mp4"), 0.25, Path.of("cap.jpg"))

                arguments.subList(1, 3) shouldBe listOf("-ss", "0.250")
            } finally {
                Locale.setDefault(locale)
            }
        }
    })
