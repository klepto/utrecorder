package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.config.DirectoriesConfig
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

                workspace("probe", config = DirectoriesConfig(root / "named")) {
                    directory = it.directory
                    existed = it.directory.exists()
                }

                existed shouldBe true
                directory.parent shouldBe root / "named" / "workspace"
                directory.fileName.toString() shouldMatch Regex("""probe-\d{4}-\d{2}-\d{2}-\d{4}-\d{2}""")
            }

            test("names files and temporary directories inside its directory without a prefix") {
                workspace("plain", config = DirectoriesConfig(root / "plain")) {
                    this["make.log"] shouldBe directory / "make.log"
                    tempDirectory().parent shouldBe directory
                }
            }

            test("keeps the directory, a tracked directory and its contents, and passes the result through when the block returns") {
                lateinit var directory: Path
                lateinit var tracked: Path

                val result =
                    workspace("success", config = DirectoriesConfig(root / "success")) {
                        directory = it.directory
                        tracked = tempDirectory()
                        (tracked / "make.log").writeText("log")
                        "the result"
                    }

                result shouldBe "the result"
                directory.shouldExist()
                (tracked / "make.log").readText() shouldBe "log"
            }

            test("keeps the directory and rethrows when the block throws") {
                lateinit var directory: Path

                shouldThrow<IllegalStateException> {
                    workspace("failure", config = DirectoriesConfig(root / "failure")) {
                        directory = it.directory
                        this["make.log"].writeText("log")
                        error("Boom")
                    }
                }

                (directory / "make.log").readText() shouldBe "log"
            }

            test("removes its directory when the block leaves it empty") {
                lateinit var directory: Path

                workspace("empty", config = DirectoriesConfig(root / "empty")) {
                    directory = it.directory
                    tempDirectory().toFile().delete()
                }

                directory.shouldNotExist()
            }

            test("gives two workspaces of the same name different directories") {
                val config = DirectoriesConfig(root / "twice")

                val first = workspace("twice", config = config) { it.directory }
                val second = workspace("twice", config = config) { it.directory }

                first shouldNotBe second
            }
        }

        context("a workspace in a shared root") {
            test("names files in the root with the name and id of the workspace as a prefix") {
                val shared = root / "shared-names"

                workspace("ucc", shared, DirectoriesConfig(root / "shared-names-home")) {
                    directory shouldBe shared
                    this["make.ini"].parent shouldBe shared
                    this["make.ini"].fileName.toString() shouldMatch Regex("""ucc-\d{4}-\d{2}-\d{2}-\d{4}-\d{2}-make\.ini""")
                }
            }

            test("moves the files and directories it named into its workspace directory and keeps the rest of the root") {
                val shared = (root / "shared-success").apply { createDirectories() }
                val foreign = (shared / "UnrealTournament.ini").apply { writeText("game") }
                lateinit var ini: Path
                lateinit var packageDirectory: Path

                workspace("ucc", shared, DirectoriesConfig(root / "shared-success-home")) {
                    ini = this["make.ini"].apply { writeText("ini") }
                    packageDirectory = this["Package"].apply { createDirectories() }
                    (packageDirectory / "Package.uc").writeText("class Package extends Object;")
                    (packageDirectory / "Classes").createDirectories()
                }

                shared.listDirectoryEntries() shouldBe listOf(foreign)
                val kept = (root / "shared-success-home" / "workspace").listDirectoryEntries().single()
                (kept / ini.fileName).readText() shouldBe "ini"
                (kept / packageDirectory.fileName / "Package.uc").readText() shouldBe "class Package extends Object;"
                (kept / packageDirectory.fileName / "Classes").shouldExist()
            }

            test("still moves the files it named when the surrounding coroutine, not just the block, is cancelled") {
                val shared = (root / "shared-cancel").apply { createDirectories() }
                val wroteFile = CompletableDeferred<Unit>()

                coroutineScope {
                    val job =
                        launch {
                            workspace("ucc", shared, DirectoriesConfig(root / "shared-cancel-home")) {
                                this["make.log"].writeText("log")
                                wroteFile.complete(Unit)
                                awaitCancellation()
                            }
                        }
                    wroteFile.await()
                    job.cancelAndJoin()
                }

                shared.listDirectoryEntries() shouldBe emptyList()
                (root / "shared-cancel-home" / "workspace").listDirectoryEntries().single().listDirectoryEntries().size shouldBe 1
            }

            test("removes its workspace directory when it named nothing in the root") {
                val shared = (root / "shared-empty").apply { createDirectories() }

                workspace("ucc", shared, DirectoriesConfig(root / "shared-empty-home")) {}

                (root / "shared-empty-home" / "workspace").listDirectoryEntries() shouldBe emptyList()
            }
        }

        context("delete") {
            test("removes a tracked directory with the tracked paths under it, and the directory of its own") {
                lateinit var directory: Path

                workspace("delete", config = DirectoriesConfig(root / "delete")) {
                    directory = it.directory
                    val tracked = tempDirectory()
                    track((tracked / "make.log").apply { writeText("log") })
                    track((tracked / "empty").apply { createDirectories() })
                    delete()
                }

                directory.shouldNotExist()
            }
        }

        context("move") {
            test("moves tracked directories into the target by their location relative to the workspace, empty ones included") {
                val target = root / "move-target"
                lateinit var directory: Path

                workspace("move", config = DirectoriesConfig(root / "move")) {
                    directory = it.directory
                    val nested = (directory / "sub" / "nested").apply { createDirectories() }
                    track(nested)
                    (nested / "make.log").writeText("log")
                    this["empty"].createDirectories()
                    move(target)
                }

                (target / "sub" / "nested" / "make.log").readText() shouldBe "log"
                (target / "empty").shouldExist()
                directory.shouldNotExist()
            }
        }

        context("prune") {
            test("removes an entry of the workspace directory older than 7 days when a workspace starts, and keeps a fresh one") {
                val stale = (root / "prune-owned" / "workspace" / "stale").apply { createDirectories() }
                val fresh = (root / "prune-owned" / "workspace" / "fresh").apply { createDirectories() }
                expire(stale)

                workspace("trigger", config = DirectoriesConfig(root / "prune-owned")) {}

                stale.shouldNotExist()
                fresh.shouldExist()
            }

            test("removes an expired file of a shared root only when it carries the workspace name and an id") {
                val shared = (root / "prune-shared").apply { createDirectories() }
                val own = (shared / "ucc-2020-01-01-0000-00-make.ini").apply { writeText("") }
                val otherName = (shared / "ut-2020-01-01-0000-00-make.ini").apply { writeText("") }
                val foreign = (shared / "UCC.log").apply { writeText("") }
                listOf(own, otherName, foreign).forEach(::expire)

                workspace("ucc", shared, DirectoriesConfig(root / "prune-shared-home")) {}

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
                        workspace("trigger", config = DirectoriesConfig(root / "prune-held")) { ran = true }
                    } finally {
                        stream.close()
                    }

                    ran shouldBe true
                    removable.shouldNotExist()
                }
        }
    })
