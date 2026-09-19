package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.os.OperatingSystem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestCase
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText

class WorkspaceTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-workspace-test")

        afterSpec { root.toFile().deleteRecursively() }

        val windowsOnly = { _: TestCase -> OperatingSystem.current() == OperatingSystem.WINDOWS }

        fun expire(path: Path) = path.setLastModifiedTime(FileTime.from(Instant.now().minus(8, ChronoUnit.DAYS)))

        context("a workspace with a directory of its own") {
            test("runs the block in an existing directory under the workspace directory, named after the workspace and its id") {
                lateinit var directory: Path
                var existed = false

                workspace("probe", directories = Directories(root / "named")) {
                    directory = it
                    existed = it.exists()
                }

                existed shouldBe true
                directory.parent shouldBe root / "named" / "workspace"
                directory.fileName.toString() shouldMatch Regex("""probe-\d{4}-\d{2}-\d{2}-\d{4}-\d{2}""")
            }

            test("names files and directories inside its directory without a prefix") {
                workspace("plain", directories = Directories(root / "plain")) { directory ->
                    file("make.log") shouldBe directory / "make.log"
                    directory("logs") shouldBe directory / "logs"
                    (directory / "logs").shouldExist()
                }
            }

            test("removes the directory and passes the result through when the block returns") {
                lateinit var directory: Path

                val result =
                    workspace("success", directories = Directories(root / "success")) {
                        directory = it
                        (it / "make.log").writeText("log")
                        "the result"
                    }

                result shouldBe "the result"
                directory.shouldNotExist()
            }

            test("keeps the directory and rethrows when the block throws") {
                lateinit var directory: Path

                shouldThrow<IllegalStateException> {
                    workspace("failure", directories = Directories(root / "failure")) {
                        directory = it
                        (it / "make.log").writeText("log")
                        error("Boom")
                    }
                }

                (directory / "make.log").readText() shouldBe "log"
            }

            test("removes the directory and rethrows when the block is cancelled") {
                lateinit var directory: Path

                shouldThrow<CancellationException> {
                    workspace("cancelled", directories = Directories(root / "cancelled")) {
                        directory = it
                        throw CancellationException("Cancelled")
                    }
                }

                directory.shouldNotExist()
            }

            test("still removes the directory when the surrounding coroutine, not just the block, is cancelled") {
                lateinit var directory: Path
                val wroteFile = CompletableDeferred<Unit>()

                coroutineScope {
                    val job =
                        launch {
                            workspace("real-cancel", directories = Directories(root / "real-cancel")) {
                                directory = it
                                (it / "make.log").writeText("log")
                                wroteFile.complete(Unit)
                                awaitCancellation()
                            }
                        }
                    wroteFile.await()
                    job.cancelAndJoin()
                }

                directory.shouldNotExist()
            }

            test("gives two workspaces of the same name different directories") {
                val directories = Directories(root / "twice")

                val first = workspace("twice", directories = directories) { it }
                val second = workspace("twice", directories = directories) { it }

                first shouldNotBe second
            }
        }

        context("a workspace in a shared root") {
            test("names files in the root with the name and id of the workspace as a prefix") {
                val shared = root / "shared-names"

                workspace("ucc", shared, Directories(root / "shared-names-home")) { directory ->
                    directory shouldBe shared
                    file("make.ini").parent shouldBe shared
                    file("make.ini").fileName.toString() shouldMatch Regex("""ucc-\d{4}-\d{2}-\d{2}-\d{4}-\d{2}-make\.ini""")
                }
            }

            test("removes the files it named and keeps the rest of the root when the block returns") {
                val shared = (root / "shared-success").apply { createDirectories() }
                val foreign = (shared / "UnrealTournament.ini").apply { writeText("game") }

                workspace("ucc", shared, Directories(root / "shared-success-home")) {
                    file("make.ini").writeText("ini")
                    (directory("Package") / "Package.uc").writeText("class Package extends Object;")
                }

                shared.listDirectoryEntries() shouldBe listOf(foreign)
            }

            test("moves the files it named into a workspace directory without their prefix when the block throws") {
                val shared = (root / "shared-failure").apply { createDirectories() }
                val foreign = (shared / "UnrealTournament.ini").apply { writeText("game") }

                shouldThrow<IllegalStateException> {
                    workspace("ucc", shared, Directories(root / "shared-failure-home")) {
                        file("make.log").writeText("log")
                        error("Boom")
                    }
                }

                shared.listDirectoryEntries() shouldBe listOf(foreign)
                val kept = (root / "shared-failure-home" / "workspace").listDirectoryEntries().single()
                kept.fileName.toString() shouldMatch Regex("""ucc-\d{4}-\d{2}-\d{2}-\d{4}-\d{2}""")
                (kept / "make.log").readText() shouldBe "log"
            }
        }

        context("prune") {
            test("removes an entry of the workspace directory older than 7 days when a workspace starts, and keeps a fresh one") {
                val stale = (root / "prune-owned" / "workspace" / "stale").apply { createDirectories() }
                val fresh = (root / "prune-owned" / "workspace" / "fresh").apply { createDirectories() }
                expire(stale)

                workspace("trigger", directories = Directories(root / "prune-owned")) {}

                stale.shouldNotExist()
                fresh.shouldExist()
            }

            test("removes an expired file of a shared root only when it carries the workspace name and an id") {
                val shared = (root / "prune-shared").apply { createDirectories() }
                val own = (shared / "ucc-2020-01-01-0000-00-make.ini").apply { writeText("") }
                val otherName = (shared / "ut-2020-01-01-0000-00-make.ini").apply { writeText("") }
                val foreign = (shared / "UCC.log").apply { writeText("") }
                listOf(own, otherName, foreign).forEach(::expire)

                workspace("ucc", shared, Directories(root / "prune-shared-home")) {}

                own.shouldNotExist()
                otherName.shouldExist()
                foreign.shouldExist()
            }

            test("runs the block when an expired entry cannot be removed, and removes the others")
                .config(enabledIf = windowsOnly) {
                    val held = (root / "prune-held" / "workspace" / "held").apply { createDirectories() }
                    val removable = (root / "prune-held" / "workspace" / "removable").apply { createDirectories() }
                    val stream = FileOutputStream((held / "held").toFile())
                    expire(held)
                    expire(removable)
                    var ran = false

                    try {
                        workspace("trigger", directories = Directories(root / "prune-held")) { ran = true }
                    } finally {
                        stream.close()
                    }

                    ran shouldBe true
                    removable.shouldNotExist()
                }
        }
    })
