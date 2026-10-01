package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.ut99.UtDirectory.System
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

class UtProcessTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utprocess-test")

        afterSpec { root.toFile().deleteRecursively() }

        fun installationWithoutIni(name: String): UtInstallation {
            val directory = root / name
            (directory / System.name / UtPlatform.current().gameExecutable).createParentDirectories().writeText("")
            val directoriesConfig = DirectoriesConfig(ConfigReader.EMPTY, home = root / "home")
            return UtInstallation(directory, UtConfig(ConfigReader.EMPTY, directoriesConfig))
        }

        test("drain fails with the failure of a launch that never started the process") {
            val process = installationWithoutIni("failed-launch").process("failed")

            val launchFailure = shouldThrow<IllegalStateException> { process.launch() }

            val drainFailure = shouldThrow<IllegalStateException> { withTimeout(10.seconds) { process.drain().toList() } }
            drainFailure.message shouldBe launchFailure.message
        }

        test("a second concurrent launch fails as already launched") {
            val process = installationWithoutIni("concurrent-launch").process("concurrent")

            val messages =
                coroutineScope {
                    List(2) { async { runCatching { process.launch() }.exceptionOrNull()?.message } }.awaitAll()
                }

            messages.count { it == "Process concurrent has already been launched" } shouldBe 1
        }
    })
