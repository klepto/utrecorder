package dev.klepto.utrecorder.ut99

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [UtDirectory.of] and the package directories [UtDirectory.packages] lists.
 */
class UtDirectoryTest :
    FunSpec({
        test("of maps each package extension to its directory") {
            UtDirectory.of("u") shouldBe UtDirectory.System
            UtDirectory.of("unr") shouldBe UtDirectory.Maps
            UtDirectory.of("utx") shouldBe UtDirectory.Textures
            UtDirectory.of("uax") shouldBe UtDirectory.Sounds
            UtDirectory.of("umx") shouldBe UtDirectory.Music
        }

        test("of maps an extension to its directory ignoring case") {
            UtDirectory.of("U") shouldBe UtDirectory.System
            UtDirectory.of("Unr") shouldBe UtDirectory.Maps
            UtDirectory.of("UTX") shouldBe UtDirectory.Textures
        }

        test("of gives null for an extension nothing claims") {
            UtDirectory.of("uz") shouldBe null
            UtDirectory.of("") shouldBe null
        }

        test("packages holds the five package directories, System first, in probe order") {
            UtDirectory.packages shouldBe
                listOf(
                    UtDirectory.System,
                    UtDirectory.Maps,
                    UtDirectory.Textures,
                    UtDirectory.Sounds,
                    UtDirectory.Music,
                )
        }
    })
