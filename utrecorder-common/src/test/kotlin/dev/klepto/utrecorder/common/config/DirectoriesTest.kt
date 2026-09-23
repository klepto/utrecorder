package dev.klepto.utrecorder.common.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div

class DirectoriesTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-directories-test").toRealPath()

        afterSpec { root.toFile().deleteRecursively() }

        test("cache is a cache directory under home") {
            DirectoriesConfig(root).cache shouldBe root / "cache"
        }

        test("installs is an installs directory under home") {
            DirectoriesConfig(root).installs shouldBe root / "installs"
        }

        test("workspace is a workspace directory under home") {
            DirectoriesConfig(root).workspace shouldBe root / "workspace"
        }

        test("packages is a packages directory under home") {
            DirectoriesConfig(root).packages shouldBe root / "packages"
        }

        test("resolves the home its reader configures") {
            val properties = mapOf("utrecorder.home" to "${root / "configured"}")
            val reader = SystemConfigReader(properties = properties::get, environment = { null })

            DirectoriesConfig(reader).home shouldBe root / "configured"
        }
    })
