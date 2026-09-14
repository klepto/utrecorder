package dev.klepto.utrecorder.common.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeText

class DirectoriesTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-directories-test").toRealPath()

        afterSpec { root.toFile().deleteRecursively() }

        fun source(
            properties: Map<String, String> = emptyMap(),
            environment: Map<String, String> = emptyMap(),
        ) = PropertySource(properties = properties::get, environment = environment::get)

        test("cache is a cache directory under home") {
            Directories(root).cache shouldBe root / "cache"
        }

        test("installs is an installs directory under home") {
            Directories(root).installs shouldBe root / "installs"
        }

        test("tasks is a tasks directory under home") {
            Directories(root).tasks shouldBe root / "tasks"
        }

        test("packages is a packages directory under home") {
            Directories(root).packages shouldBe root / "packages"
        }

        test("output is an output directory under home") {
            Directories(root).output shouldBe root / "output"
        }

        context("fromProperties") {
            test("resolves the home the source configures before anything else") {
                val checkout = (root / "configured-checkout").apply { createDirectories() }
                (checkout / "settings.gradle.kts").writeText("")
                val source =
                    source(
                        properties = mapOf("utrecorder.home" to "${root / "configured"}", "user.dir" to "$checkout"),
                    )

                Directories.fromProperties(source).home shouldBe root / "configured"
            }

            test("resolves .utrecorder in the checkout holding the working directory") {
                val checkout = (root / "checkout").apply { createDirectories() }
                (checkout / "settings.gradle.kts").writeText("")
                val module = (checkout / "module" / "src").apply { createDirectories() }
                val source = source(properties = mapOf("user.dir" to "$module", "os.name" to "Linux"))

                Directories.fromProperties(source).home shouldBe checkout / ".utrecorder"
            }

            test("resolves utrecorder under the data directory of the operating system os.name names") {
                val outside = (root / "outside").apply { createDirectories() }
                val source =
                    source(
                        properties = mapOf("user.dir" to "$outside", "os.name" to "Linux", "user.home" to "$root"),
                        environment = mapOf("LOCALAPPDATA" to "${root / "local-app-data"}"),
                    )

                Directories.fromProperties(source).home shouldBe root / ".local" / "share" / "utrecorder"
            }
        }
    })
