package dev.klepto.utrecorder.common.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div

/**
 * Tests for [Directories] and the directories it derives from the home it is built around.
 */
class DirectoriesTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-directories-test")

        val property = SystemProperties()

        afterSpec { home.toFile().deleteRecursively() }
        afterTest { property.clear() }

        test("cache is a cache directory under home") {
            Directories(home).cache shouldBe home / "cache"
        }

        test("installs is an installs directory under home") {
            Directories(home).installs shouldBe home / "installs"
        }

        test("tasks is a tasks directory under home") {
            Directories(home).tasks shouldBe home / "tasks"
        }

        test("packages is a packages directory under home") {
            Directories(home).packages shouldBe home / "packages"
        }

        test("output is an output directory under home") {
            Directories(home).output shouldBe home / "output"
        }

        test("fromProperties honours utrecorder.home") {
            val configuredHome = (home / "configured").toAbsolutePath().normalize()
            property("utrecorder.home", configuredHome.toString())

            Directories.fromProperties(PropertySource()).home shouldBe configuredHome
        }
    })
