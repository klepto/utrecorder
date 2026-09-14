package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.config.PropertySource
import dev.klepto.utrecorder.common.os.OperatingSystem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class WorkspaceTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-workspace-test")

        afterSpec { root.toFile().deleteRecursively() }

        fun workspace(
            directory: Path,
            keep: Boolean = false,
        ) = Workspace(directory, keep, 7.days)

        fun expire(directory: Path) = directory.setLastModifiedTime(FileTime.from(Instant.now().minus(8, ChronoUnit.DAYS)))

        val windowsOnly = { _: io.kotest.core.test.TestCase -> OperatingSystem.current() == OperatingSystem.WINDOWS }

        context("task") {
            test("the block receives a directory that exists, under the workspace directory, named with the task name as a prefix") {
                val directory = root / "probe-tasks"
                lateinit var taskDirectory: Path
                var existedDuringBlock = false

                workspace(directory).task("probe") {
                    taskDirectory = it
                    existedDuringBlock = it.toFile().exists()
                }

                existedDuringBlock shouldBe true
                taskDirectory.parent shouldBe directory
                taskDirectory.fileName.toString() shouldStartWith "probe-"
            }

            test("a task on a workspace whose directory does not exist yet creates it and runs the block") {
                val directory = root / "missing-tasks" / "nested"
                var ran = false

                workspace(directory).task("missing") { ran = true }

                ran shouldBe true
                directory.shouldExist()
            }

            test("a task on a workspace whose directory is a regular file throws before the block runs") {
                val directory = (root / "file-tasks").apply { writeText("") }
                var ran = false

                shouldThrow<IOException> { workspace(directory).task("file") { ran = true } }

                ran shouldBe false
            }

            test("a successful task removes its directory and passes through the block's return value") {
                lateinit var taskDirectory: Path

                val result =
                    workspace(root / "cleanup-tasks").task("cleanup") {
                        taskDirectory = it
                        "the result"
                    }

                result shouldBe "the result"
                taskDirectory.shouldNotExist()
            }

            test("a failing task keeps its directory and rethrows") {
                lateinit var taskDirectory: Path

                shouldThrow<IllegalStateException> {
                    workspace(root / "failing-tasks").task("failing") {
                        taskDirectory = it
                        error("boom")
                    }
                }

                taskDirectory.shouldExist()
            }

            test("keep keeps the directory of a successful task") {
                lateinit var taskDirectory: Path

                workspace(root / "kept-tasks", keep = true).task("kept") { taskDirectory = it }

                taskDirectory.shouldExist()
            }

            test("two tasks of the same name started back to back get different directories") {
                val workspace = workspace(root / "collision-tasks", keep = true)
                lateinit var first: Path
                lateinit var second: Path

                workspace.task("collision") { first = it }
                workspace.task("collision") { second = it }

                first shouldNotBe second
            }

            test("a stale leftover directory is removed by the next task call, and a fresh one is not") {
                val directory = (root / "prune-tasks").apply { createDirectories() }
                val stale = (directory / "stale-leftover").apply { createDirectories() }
                val fresh = (directory / "fresh-leftover").apply { createDirectories() }
                expire(stale)

                workspace(directory).task("prune-trigger") {}

                stale.shouldNotExist()
                fresh.shouldExist()
            }

            test("a successful task whose block leaves a file of its directory open throws the removal failure after the block returned")
                .config(enabledIf = windowsOnly) {
                    var stream: FileOutputStream? = null
                    var returned = false

                    try {
                        shouldThrow<IOException> {
                            workspace(root / "open-tasks").task("open") {
                                stream = FileOutputStream((it / "held").toFile())
                                returned = true
                            }
                        }
                    } finally {
                        stream?.close()
                    }

                    returned shouldBe true
                }

            test("an expired directory holding an open file fails the next task before its block runs, while another beside it is removed")
                .config(enabledIf = windowsOnly) {
                    val directory = (root / "held-prune-tasks").apply { createDirectories() }
                    val held = (directory / "held-leftover").apply { createDirectories() }
                    val removable = (directory / "removable-leftover").apply { createDirectories() }
                    val stream = FileOutputStream((held / "held").toFile())
                    expire(held)
                    expire(removable)
                    var ran = false

                    try {
                        shouldThrow<IOException> { workspace(directory).task("held-prune") { ran = true } }
                    } finally {
                        stream.close()
                    }

                    ran shouldBe false
                    removable.shouldNotExist()
                }
        }

        context("fromProperties") {
            val directories = Directories(root / "fromproperties-home")

            fun source(vararg properties: Pair<String, String>) =
                PropertySource(properties = mapOf(*properties)::get, environment = { null })

            test("derives directory from the tasks directory of the given directories") {
                Workspace.fromProperties(source(), directories).directory shouldBe directories.tasks
            }

            test("honours tasks.keep") {
                Workspace.fromProperties(source("utrecorder.tasks.keep" to "true"), directories).keep shouldBe true
            }

            test("honours tasks.retention") {
                Workspace.fromProperties(source("utrecorder.tasks.retention" to "30m"), directories).retention shouldBe 30.minutes
            }

            test("falls back to not keeping and 7 days") {
                val workspace = Workspace.fromProperties(source(), directories)

                workspace.keep shouldBe false
                workspace.retention shouldBe 7.days
            }
        }
    })
