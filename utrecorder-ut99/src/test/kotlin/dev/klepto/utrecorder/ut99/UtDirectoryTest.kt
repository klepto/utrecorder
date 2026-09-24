package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.ut99.UtDirectory.Maps
import dev.klepto.utrecorder.ut99.UtDirectory.Music
import dev.klepto.utrecorder.ut99.UtDirectory.Sounds
import dev.klepto.utrecorder.ut99.UtDirectory.System
import dev.klepto.utrecorder.ut99.UtDirectory.Textures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UtDirectoryTest :
    FunSpec({
        test("of returns the directory holding packages of each extension") {
            UtDirectory.of("u") shouldBe System
            UtDirectory.of("unr") shouldBe Maps
            UtDirectory.of("utx") shouldBe Textures
            UtDirectory.of("uax") shouldBe Sounds
            UtDirectory.of("umx") shouldBe Music
        }

        test("of compares the extension ignoring case") {
            UtDirectory.of("U") shouldBe System
            UtDirectory.of("Unr") shouldBe Maps
            UtDirectory.of("UTX") shouldBe Textures
        }

        test("of returns null for an extension no directory holds packages of") {
            UtDirectory.of("uz") shouldBe null
            UtDirectory.of("") shouldBe null
        }

        test("PACKAGES lists System, Maps, Textures, Sounds and Music in lookup order") {
            UtDirectory.PACKAGES shouldBe listOf(System, Maps, Textures, Sounds, Music)
        }

        test("PACKAGE_EXTENSIONS lists u, unr, utx, uax and umx in lookup order") {
            UtDirectory.PACKAGE_EXTENSIONS.toList() shouldBe listOf("u", "unr", "utx", "uax", "umx")
        }
    })
