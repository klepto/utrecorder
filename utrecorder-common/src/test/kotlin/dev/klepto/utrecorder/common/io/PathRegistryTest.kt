package dev.klepto.utrecorder.common.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

class PathRegistryTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-path-registry-test")

        afterSpec { root.toFile().deleteRecursively() }

        test("leaves out of the roots a tracked path that lies under another tracked path") {
            val directory = (root / "roots").apply { createDirectories() }
            val registry = PathRegistry("", directory)
            val parent = registry.newFile("parent").apply { createDirectories() }
            registry.register((parent / "child.log").apply { writeText("log") })
            val sibling = registry.newFile("sibling.log").apply { writeText("log") }

            registry.getRoots() shouldBe setOf(parent.toAbsolutePath(), sibling.toAbsolutePath())
        }

        test("relativizes a path under the registry directory to its location there, and a path outside it to its file name") {
            val directory = root / "relativize"
            val registry = PathRegistry("", directory)

            registry.relativize(directory / "sub" / "a.log") shouldBe Path.of("sub", "a.log")
            registry.relativize(root / "elsewhere" / "b.log") shouldBe Path.of("b.log")
        }

        test("removes a tracked directory together with the tracked paths under it, empty directories included") {
            val directory = (root / "delete").apply { createDirectories() }
            val registry = PathRegistry("", directory)
            val parent = registry.tempDirectory()
            registry.register((parent / "child.log").apply { writeText("log") })
            registry.register((parent / "empty").apply { createDirectories() })
            registry.tempDirectory()

            registry.delete()

            parent.shouldNotExist()
            directory.listDirectoryEntries() shouldBe emptyList()
        }
    })
