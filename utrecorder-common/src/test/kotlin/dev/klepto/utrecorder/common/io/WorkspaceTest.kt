package dev.klepto.utrecorder.common.io

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.OperatingSystem
import dev.klepto.utrecorder.common.OperatingSystem.WINDOWS
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestCase
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
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

        val windowsOnly = { _: TestCase -> OperatingSystem.current() == WINDOWS }

        fun expire(path: Path) = path.setLastModifiedTime(FileTime.from(Instant.now().minus(4, ChronoUnit.DAYS)))

        // Holds the directory of every id the next 10 minutes can produce, as another process sharing the home would.
        fun claimAhead(
            name: String,
            config: DirectoriesConfig,
        ): Set<Path> {
            val format = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm-ss").withZone(ZoneId.systemDefault())
            val now = Instant.now()
            return (0L..600L)
                .map { config.workspace / "$name-${format.format(now.plusSeconds(it))}" }
                .onEach { (it / "foreign.log").createParentDirectories().writeText("foreign") }
                .toSet()
        }

        // Coroutine stack trace recovery can rethrow a copy of the failure, with the original as its cause.
        fun suppressed(failure: Throwable): List<Throwable> =
            generateSequence(failure, Throwable::cause)
                .flatMap { it.suppressed.asSequence() }
                .toList()

        context("a workspace with a directory of its own") {
            test("runs the block in an existing directory under the workspace directory, named after the workspace and its id") {
                lateinit var directory: Path
                var existed = false

                workspace("probe", config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "named")) {
                    directory = it.directory
                    existed = it.directory.exists()
                }

                existed shouldBe true
                directory.parent shouldBe root / "named" / "workspace"
                directory.fileName.toString() shouldMatch Regex("""probe-\d{4}-\d{2}-\d{2}-\d{4}-\d{2}""")
            }

            test("names files and temporary directories inside its directory without a prefix") {
                workspace("plain", config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "plain")) {
                    this["make.log"] shouldBe directory / "make.log"
                    tempDirectory().parent shouldBe directory
                }
            }

            test("returns the result of the block and keeps the directory with the tracked files in it") {
                lateinit var directory: Path
                lateinit var tracked: Path

                val result =
                    workspace("success", config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "success")) {
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
                    workspace("failure", config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "failure")) {
                        directory = it.directory
                        this["make.log"].writeText("log")
                        error("Boom")
                    }
                }

                (directory / "make.log").readText() shouldBe "log"
            }

            test("removes its directory when the block leaves it empty") {
                lateinit var directory: Path

                workspace("empty", config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "empty")) {
                    directory = it.directory
                    tempDirectory().toFile().delete()
                }

                directory.shouldNotExist()
            }

            test("gives two workspaces of the same name different directories") {
                val config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "twice")

                val first = workspace("twice", config = config) { it.directory }
                val second = workspace("twice", config = config) { it.directory }

                first shouldNotBe second
            }

            test("skips an id whose directory another process already holds") {
                val config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "claimed")
                val taken = claimAhead("claimed", config)
                lateinit var directory: Path
                lateinit var entries: List<Path>

                workspace("claimed", config = config) {
                    directory = it.directory
                    entries = it.directory.listDirectoryEntries()
                    this["make.log"].writeText("log")
                }

                taken.contains(directory) shouldBe false
                entries shouldBe emptyList()
                taken.forEach { it.listDirectoryEntries() shouldBe listOf(it / "foreign.log") }
            }
        }

        context("a workspace in a shared root") {
            test("names files in the root with the name and id of the workspace as a prefix") {
                val shared = root / "shared-names"

                workspace("ucc", shared, DirectoriesConfig(ConfigReader.EMPTY, home = root / "shared-names-home")) {
                    directory shouldBe shared
                    this["make.ini"].parent shouldBe shared
                    this["make.ini"].fileName.toString() shouldMatch Regex("""ucc-\d{4}-\d{2}-\d{2}-\d{4}-\d{2}-make\.ini""")
                }
            }

            test("moves the files and directories it named into its workspace directory and keeps the rest of the root") {
                val shared = (root / "shared-success").createDirectories()
                val foreign = (shared / "UnrealTournament.ini").apply { writeText("game") }
                lateinit var ini: Path
                lateinit var packageDirectory: Path

                workspace("ucc", shared, DirectoriesConfig(ConfigReader.EMPTY, home = root / "shared-success-home")) {
                    ini = this["make.ini"].apply { writeText("ini") }
                    packageDirectory = this["Package"].createDirectories()
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
                val shared = (root / "shared-cancel").createDirectories()
                val wroteFile = CompletableDeferred<Unit>()

                coroutineScope {
                    val job =
                        launch {
                            workspace("ucc", shared, DirectoriesConfig(ConfigReader.EMPTY, home = root / "shared-cancel-home")) {
                                this["make.log"].writeText("log")
                                wroteFile.complete(Unit)
                                awaitCancellation()
                            }
                        }
                    wroteFile.await()
                    job.cancelAndJoin()
                }

                shared.listDirectoryEntries() shouldBe emptyList()
                (root / "shared-cancel-home" / "workspace")
                    .listDirectoryEntries()
                    .single()
                    .listDirectoryEntries()
                    .size shouldBe 1
            }

            test("rethrows the failure of the block with the failure to move its files suppressed") {
                val shared = (root / "shared-move-failure").createDirectories()
                val config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "shared-move-failure-home")

                val failure =
                    shouldThrow<IllegalStateException> {
                        workspace("ucc", shared, config) {
                            val log = this["make.log"].apply { writeText("log") }
                            val claimed = config.workspace / log.fileName.toString().removeSuffix("-make.log")
                            claimed.deleteExisting()
                            claimed.writeText("blocker")
                            error("Boom")
                        }
                    }

                failure.message shouldBe "Boom"
                suppressed(failure).single().shouldBeInstanceOf<FileAlreadyExistsException>()
            }

            test("rethrows a cancellation of the block with the failure to move its files suppressed") {
                val shared = (root / "shared-cancel-failure").createDirectories()
                val config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "shared-cancel-failure-home")

                val failure =
                    shouldThrow<CancellationException> {
                        workspace("ucc", shared, config) {
                            val log = this["make.log"].apply { writeText("log") }
                            val claimed = config.workspace / log.fileName.toString().removeSuffix("-make.log")
                            claimed.deleteExisting()
                            claimed.writeText("blocker")
                            throw CancellationException("Stop")
                        }
                    }

                failure.message shouldBe "Stop"
                suppressed(failure).single().shouldBeInstanceOf<FileAlreadyExistsException>()
            }

            test("removes its workspace directory when it named nothing in the root") {
                val shared = (root / "shared-empty").createDirectories()

                workspace("ucc", shared, DirectoriesConfig(ConfigReader.EMPTY, home = root / "shared-empty-home")) {}

                (root / "shared-empty-home" / "workspace").listDirectoryEntries() shouldBe emptyList()
            }

            test("names no file with an id whose directory another process already holds") {
                val shared = (root / "shared-claimed").createDirectories()
                val config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "shared-claimed-home")
                val taken = claimAhead("ucc", config).map { it.fileName.toString() }
                lateinit var log: Path

                workspace("ucc", shared, config) {
                    log = this["make.log"].apply { writeText("log") }
                }

                taken.contains(log.fileName.toString().removeSuffix("-make.log")) shouldBe false
            }
        }

        context("a nested workspace") {
            test("names files in the directory of its parent with the name and id of the workspace as a prefix") {
                val config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "nested-names")

                workspace("record", config = config) { recording ->
                    workspace("video", config = config) {
                        directory shouldBe recording.directory
                        this["log.txt"].parent shouldBe recording.directory
                        this["log.txt"].fileName.toString() shouldMatch Regex("""video-\d{4}-\d{2}-\d{2}-\d{4}-\d{2}-log\.txt""")
                    }
                }
            }

            test("keeps the files of two nested workspaces that name the same file apart, in the directory of their parent") {
                val config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "nested-apart")
                lateinit var directory: Path

                workspace("record", config = config) { recording ->
                    directory = recording.directory
                    workspace("video", config = config) { this["log.txt"].writeText("video") }
                    workspace("audio", config = config) { this["log.txt"].writeText("audio") }
                }

                directory.listDirectoryEntries().map { it.readText() }.sorted() shouldBe listOf("audio", "video")
                (root / "nested-apart" / "workspace").listDirectoryEntries() shouldBe listOf(directory)
            }

            test("leaves an expired entry of the workspace directory to the top-level workspace") {
                val config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "nested-prune")
                lateinit var stale: Path

                workspace("record", config = config) {
                    stale = (config.workspace / "stale").createDirectories()
                    expire(stale)
                    workspace("video", config = config) {}
                }

                stale.shouldExist()
            }
        }

        context("delete") {
            test("removes the tracked files and the directory of its own") {
                lateinit var directory: Path

                workspace("delete", config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "delete")) {
                    directory = it.directory
                    val tracked = tempDirectory()
                    track((tracked / "make.log").apply { writeText("log") })
                    track((tracked / "empty").createDirectories())
                    delete()
                }

                directory.shouldNotExist()
            }
        }

        context("move") {
            test(
                "moves the tracked files into the target by their location relative to the directory, empty directories included, and removes the workspace",
            ) {
                val target = root / "move-target"
                lateinit var directory: Path

                workspace("move", config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "move")) {
                    directory = it.directory
                    val nested = (directory / "sub" / "nested").createDirectories()
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
            test("removes an entry of the workspace directory older than 3 days when a workspace starts, and keeps a fresh one") {
                val stale = (root / "prune-owned" / "workspace" / "stale").createDirectories()
                val fresh = (root / "prune-owned" / "workspace" / "fresh").createDirectories()
                expire(stale)

                workspace("trigger", config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "prune-owned")) {}

                stale.shouldNotExist()
                fresh.shouldExist()
            }

            test("removes an expired file of a shared root only when it carries the workspace name and an id") {
                val shared = (root / "prune-shared").createDirectories()
                val own = (shared / "ucc-2020-01-01-0000-00-make.ini").apply { writeText("") }
                val otherName = (shared / "ut-2020-01-01-0000-00-make.ini").apply { writeText("") }
                val foreign = (shared / "UCC.log").apply { writeText("") }
                listOf(own, otherName, foreign).forEach(::expire)

                workspace("ucc", shared, DirectoriesConfig(ConfigReader.EMPTY, home = root / "prune-shared-home")) {}

                own.shouldNotExist()
                otherName.shouldExist()
                foreign.shouldExist()
            }

            test("runs the block when an expired entry cannot be removed, and removes the others")
                .config(enabledIf = windowsOnly) {
                    val held = (root / "prune-held" / "workspace" / "held").createDirectories()
                    val removable = (root / "prune-held" / "workspace" / "removable").createDirectories()
                    var ran = false

                    FileOutputStream((held / "held").toFile()).use {
                        expire(held)
                        expire(removable)
                        workspace("trigger", config = DirectoriesConfig(ConfigReader.EMPTY, home = root / "prune-held")) { ran = true }
                    }

                    ran shouldBe true
                    removable.shouldNotExist()
                }
        }
    })
