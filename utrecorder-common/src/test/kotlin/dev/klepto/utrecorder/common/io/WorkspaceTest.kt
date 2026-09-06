package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.config.SystemProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.setLastModifiedTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for [Workspace.task] and the working directory it hands its block, and for [Workspace.fromProperties].
 */
class WorkspaceTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-workspace-test")
        val property = SystemProperties()

        afterSpec { root.toFile().deleteRecursively() }
        afterTest { property.clear() }

        context("task") {
            test("the block receives a directory that exists, under the workspace directory, named with the task name as a prefix") {
                val directory = root / "probe-tasks"
                val workspace = Workspace(directory = directory)
                lateinit var taskDirectory: Path
                var existedDuringBlock = false

                workspace.task("probe") {
                    taskDirectory = it
                    existedDuringBlock = it.toFile().exists()
                }

                existedDuringBlock shouldBe true
                taskDirectory.parent shouldBe directory
                taskDirectory.fileName.toString() shouldStartWith "probe-"
            }

            test("a successful task removes its directory and passes through the block's return value") {
                val workspace = Workspace(directory = root / "cleanup-tasks")
                lateinit var taskDirectory: Path

                val result =
                    workspace.task("cleanup") {
                        taskDirectory = it
                        "the result"
                    }

                result shouldBe "the result"
                taskDirectory.shouldNotExist()
            }

            test("a failing task keeps its directory and rethrows") {
                val workspace = Workspace(directory = root / "failing-tasks")
                lateinit var taskDirectory: Path

                shouldThrow<IllegalStateException> {
                    workspace.task("failing") {
                        taskDirectory = it
                        error("boom")
                    }
                }

                taskDirectory.shouldExist()
            }

            test("keep keeps the directory of a successful task") {
                val workspace = Workspace(directory = root / "kept-tasks", keep = true)
                lateinit var taskDirectory: Path

                workspace.task("kept") { taskDirectory = it }

                taskDirectory.shouldExist()
            }

            test("two tasks of the same name started back to back get different directories") {
                val workspace = Workspace(directory = root / "collision-tasks", keep = true)
                lateinit var first: Path
                lateinit var second: Path

                workspace.task("collision") { first = it }
                workspace.task("collision") { second = it }

                first shouldNotBe second
            }

            test("a stale leftover directory is removed by the next task call, and a fresh one is not") {
                val directory = (root / "prune-tasks").apply { createDirectories() }
                val workspace = Workspace(directory = directory, retention = 7.days)
                val stale = (directory / "stale-leftover").apply { createDirectories() }
                val fresh = (directory / "fresh-leftover").apply { createDirectories() }
                stale.setLastModifiedTime(FileTime.from(Instant.now().minus(8, ChronoUnit.DAYS)))

                workspace.task("prune-trigger") {}

                stale.shouldNotExist()
                fresh.shouldExist()
            }
        }

        context("fromProperties") {
            val directories = Directories(root / "fromproperties-home")

            test("derives directory from the tasks directory of the given directories") {
                Workspace.fromProperties(directories = directories).directory shouldBe directories.tasks
            }

            test("honours tasks.keep") {
                property("utrecorder.tasks.keep", "true")

                Workspace.fromProperties(directories = directories).keep shouldBe true
            }

            test("honours tasks.retention") {
                property("utrecorder.tasks.retention", "30m")

                Workspace.fromProperties(directories = directories).retention shouldBe 30.minutes
            }
        }
    })
