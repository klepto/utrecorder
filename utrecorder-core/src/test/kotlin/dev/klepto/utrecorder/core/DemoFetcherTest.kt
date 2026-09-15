package dev.klepto.utrecorder.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.klepto.utrecorder.common.config.Directories
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

class DemoFetcherTest :
    FunSpec({
        val home = createTempDirectory("utrecorder-demofetcher-test")
        val directories = Directories(home)
        val demos = directories.cache / "demos"

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val body = ByteArray(16) { it.toByte() }
        var requests = 0
        server.createContext("/") { exchange: HttpExchange ->
            requests++
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        afterSpec {
            server.stop(0)
            home.toFile().deleteRecursively()
        }

        val fetcher = DemoFetcher(directories)
        val url = "http://${server.address.address.hostAddress}:${server.address.port}/dir/demo-file.dem"

        test("a URL's cache name is its file name, query string dropped") {
            fetcher.fetch(url).fileName.name shouldBe "demo-file.dem"
        }

        test("a cache miss writes the file into the cache and returns its path") {
            val fetched = fetcher.fetch(url)

            fetched shouldBe demos / "demo-file.dem"
            fetched.readBytes() shouldBe body
        }

        test("a cache hit returns the existing file without touching the network") {
            val cached = (demos / "demo-file.dem").also { it.parent.createDirectories() }
            val other = ByteArray(8) { (it + 40).toByte() }
            cached.writeBytes(other)
            val served = requests

            val fetched = fetcher.fetch(url)

            fetched shouldBe cached
            fetched.readBytes() shouldBe other
            requests shouldBe served
        }
    })
