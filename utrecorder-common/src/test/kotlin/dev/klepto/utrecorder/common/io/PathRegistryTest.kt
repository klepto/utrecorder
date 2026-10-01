package dev.klepto.utrecorder.common.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

        test("leaves out of the roots a registered path that lies under another registered path") {
            val directory = (root / "roots").createDirectories()
            val registry = PathRegistry("", directory)
            val parent = registry.newFile("parent").createDirectories()
            registry.register((parent / "child.log").apply { writeText("log") })
            val sibling = registry.newFile("sibling.log").apply { writeText("log") }

            registry.getRoots() shouldBe setOf(parent.toAbsolutePath(), sibling.toAbsolutePath())
        }

        test("keeps every path registered from concurrent coroutines") {
            val directory = (root / "concurrent").createDirectories()
            val registry = PathRegistry("", directory)
            val paths = (0 until 2000).map { (directory / "$it.log").apply { writeText("log") } }

            coroutineScope {
                paths
                    .chunked(250)
                    .map { chunk -> launch(Dispatchers.IO) { chunk.forEach(registry::register) } }
            }

            registry.getAll().size shouldBe 2000
        }

        test("relativizes a path inside the directory to its location there, and a path outside it to its file name") {
            val directory = root / "relativize"
            val registry = PathRegistry("", directory)

            registry.relativize(directory / "sub" / "a.log") shouldBe Path.of("sub", "a.log")
            registry.relativize(root / "elsewhere" / "b.log") shouldBe Path.of("b.log")
        }

        test("removes every registered path together with everything under it, empty directories included") {
            val directory = (root / "delete").createDirectories()
            val registry = PathRegistry("", directory)
            val parent = registry.tempDirectory()
            registry.register((parent / "child.log").apply { writeText("log") })
            registry.register((parent / "empty").createDirectories())
            registry.tempDirectory()

            registry.delete()

            parent.shouldNotExist()
            directory.listDirectoryEntries() shouldBe emptyList()
        }
    })
