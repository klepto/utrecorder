package dev.klepto.utrecorder.server.recording

import com.sun.net.httpserver.HttpServer
import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.core.RecorderConfig
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.ut99.UtConfig
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.walk
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DefaultRecorderProviderTest :
    FunSpec({
        val release = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/missing") { exchange ->
            requests += exchange.requestURI.path
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.createContext("/demo.dem") { exchange ->
            val content = "dem".toByteArray()
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        server.createContext("/stalled.dem") { exchange ->
            exchange.sendResponseHeaders(200, 1000)
            exchange.responseBody.write("dem".toByteArray())
            exchange.responseBody.flush()
            release.await(30, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        afterSpec {
            release.countDown()
            server.stop(0)
        }

        fun url(name: String): String = "http://127.0.0.1:${server.address.port}/$name"

        fun demo(url: String): Demo =
            Demo(
                capId = "cap",
                map = "CTF-BT-Maverick",
                player = "klepto",
                capTime = 10.seconds,
                timestamp = Instant.parse("2026-09-26T12:00:00Z"),
                demoUrl = url,
            )

        fun directories(): DirectoriesConfig = DirectoriesConfig(ConfigReader.EMPTY, home = tempdir().toPath())

        fun provider(directories: DirectoriesConfig): DefaultRecorderProvider =
            DefaultRecorderProvider(
                RecorderConfig(
                    ConfigReader.EMPTY,
                    ut = UtConfig(ConfigReader.EMPTY, directories, isoUrl = url("missing/game.iso"), patchUrl = url("missing")),
                ),
            )

        fun files(directory: Path): List<Path> = directory.walk().filter { !it.isDirectory() }.toList()

        test("returns a failed result for a demo the server does not serve, and leaves no directory under the workspace") {
            val directories = directories()

            val result = provider(directories).record(demo(url("missing/cap.dem")))

            result.shouldBeInstanceOf<RecorderResult.Failed>()
            directories.workspace.listDirectoryEntries().shouldBeEmpty()
        }

        test("returns a failed result for a recording that fails after its demo downloaded, and leaves no directory under the workspace") {
            val directories = directories()

            val result = provider(directories).record(demo(url("demo.dem")))

            result.shouldBeInstanceOf<RecorderResult.Failed>()
            requests shouldContain "/missing/game.iso"
            directories.workspace.listDirectoryEntries().shouldBeEmpty()
        }

        test("leaves no directory under the workspace when the recording is cancelled") {
            val directories = directories()
            val provider = provider(directories)

            coroutineScope {
                val recording = async { provider.record(demo(url("stalled.dem"))) }
                eventually(10.seconds) { files(directories.workspace).shouldNotBeEmpty() }
                recording.cancelAndJoin()
            }

            directories.workspace.listDirectoryEntries().shouldBeEmpty()
        }
    })
