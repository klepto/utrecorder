package dev.klepto.utrecorder.ut99

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

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

        test("PACKAGES holds the five package directories, System first, in probe order") {
            UtDirectory.PACKAGES shouldBe
                listOf(
                    UtDirectory.System,
                    UtDirectory.Maps,
                    UtDirectory.Textures,
                    UtDirectory.Sounds,
                    UtDirectory.Music,
                )
        }

        test("PACKAGE_EXTENSIONS is u, unr, utx, uax and umx, in that order") {
            UtDirectory.PACKAGE_EXTENSIONS.toList() shouldBe listOf("u", "unr", "utx", "uax", "umx")
        }
    })
