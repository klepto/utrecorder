package dev.klepto.utrecorder.common.io

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.klepto.utrecorder.common.config.DirectoriesConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.ClientRequestException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.random.Random

class DownloaderTest :
    FunSpec({
        val sample = "utrecorder-22"
        val sampleMd5 = "00ad189b536dc412168fc866a7ec13b0"
        val sampleSha256 = "0323ec0736b6000fffd1a01bc5a70c1e35dc998f1214edde7a668879b54b9562"
        val tampered = "tampered"
        val tamperedMd5 = "07b218b924120d49a725c36b06556000"

        val root = createTempDirectory("utrecorder-downloader-test")
        val directories = DirectoriesConfig(root / "home")
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

        val notFound: (HttpExchange) -> Unit = { it.sendResponseHeaders(404, -1) }
        var handler = notFound
        var requests = 0

        server.createContext("/") { exchange ->
            requests++
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()

        afterSpec {
            server.stop(0)
            root.toFile().deleteRecursively()
        }

        beforeTest {
            handler = notFound
            requests = 0
        }

        val url = "http://${server.address.hostString}:${server.address.port}/file"

        fun serveBytes(
            bytes: ByteArray,
            chunked: Boolean = false,
        ) {
            handler = { exchange ->
                val length =
                    when {
                        chunked -> 0L
                        bytes.isEmpty() -> -1L
                        else -> bytes.size.toLong()
                    }
                if (exchange.requestMethod == "HEAD") {
                    if (length >= 0) exchange.responseHeaders.set("Content-Length", length.toString())
                    exchange.sendResponseHeaders(200, -1)
                    exchange.responseBody.close()
                } else {
                    exchange.sendResponseHeaders(200, length)
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
        }

        fun serve(
            body: String = sample,
            chunked: Boolean = false,
        ) = serveBytes(body.toByteArray(), chunked)

        fun leftovers() =
            (root / "home" / "workspace")
                .toFile()
                .walk()
                .filter { it.isFile }
                .toList()

        test("writes the response body to the output path") {
            serve()
            val output = root / "body"

            Downloader(url, config = directories).download(output)

            output.readText() shouldBe sample
        }

        test("creates missing parent directories") {
            serve()
            val output = root / "missing" / "nested" / "body"

            Downloader(url, config = directories).download(output)

            output.shouldExist()
        }

        test("transfers a body larger than the copy buffer intact") {
            val bytes = Random(22).nextBytes(4 * 1024 * 1024)
            serveBytes(bytes)
            val output = root / "large"

            Downloader(url, config = directories).download(output)

            output.readBytes() shouldBe bytes
        }

        test("writes an empty file for an empty body") {
            serve("")
            val output = root / "empty"

            Downloader(url, config = directories).download(output)

            output.readBytes().size shouldBe 0
        }

        test("reports progress with the total size when the length is known") {
            serve()
            val progress = mutableListOf<Pair<Long, Long>>()

            Downloader(url, config = directories).download(root / "progress") { received, total -> progress += received to total }

            progress.last() shouldBe (sample.length.toLong() to sample.length.toLong())
        }

        test("reports a total of -1 when the length is unknown") {
            serve(chunked = true)
            val progress = mutableListOf<Pair<Long, Long>>()

            Downloader(url, config = directories).download(root / "chunked") { received, total -> progress += received to total }

            progress.last() shouldBe (sample.length.toLong() to -1L)
        }

        test("accepts a matching md5 hash") {
            serve()
            val output = root / "md5-match"

            Downloader(url, sampleMd5, config = directories).download(output)

            output.readText() shouldBe sample
        }

        test("accepts a matching sha-256 hash") {
            serve()
            val output = root / "sha-match"

            Downloader(url, sampleSha256, algorithm = "sha-256", config = directories).download(output)

            output.readText() shouldBe sample
        }

        test("compares hashes case-insensitively") {
            serve()
            val output = root / "uppercase-hash"

            Downloader(url, sampleMd5.uppercase(), config = directories).download(output)

            output.readText() shouldBe sample
        }

        test("reports both hashes when the download does not match") {
            serve(tampered)

            val downloader = Downloader(url, sampleMd5, config = directories)

            val error = shouldThrow<HashMismatchException> { downloader.download(root / "mismatch") }

            error.expected shouldBe sampleMd5
            error.actual shouldBe tamperedMd5
            error.message shouldBe "Hash mismatch for download $url: expected $sampleMd5, actual $tamperedMd5"
        }

        test("leaves no output file when the hash does not match") {
            serve(tampered)
            val output = root / "mismatch-output"

            shouldThrow<HashMismatchException> { Downloader(url, sampleMd5, config = directories).download(output) }

            output.shouldNotExist()
            leftovers() shouldBe emptyList()
        }

        test("skips the request when the output already matches the hash") {
            serve()
            val output = (root / "cached").apply { writeText(sample) }

            Downloader(url, sampleMd5, config = directories).download(output)

            requests shouldBe 0
        }

        test("reports the cached size to the listener when the download is skipped") {
            serve()
            val output = (root / "cached-progress").apply { writeText(sample) }
            val progress = mutableListOf<Pair<Long, Long>>()

            Downloader(url, sampleMd5, config = directories).download(output) { received, total -> progress += received to total }

            progress shouldBe listOf(sample.length.toLong() to sample.length.toLong())
        }

        test("re-downloads when the existing output has a different hash") {
            serve()
            val output = (root / "stale").apply { writeText("stale") }

            Downloader(url, sampleMd5, config = directories).download(output)

            requests shouldBe 1
            output.readText() shouldBe sample
        }

        test("re-downloads an existing output when no hash is given") {
            serve()
            val output = (root / "no-hash").apply { writeText("stale") }

            Downloader(url, config = directories).download(output)

            requests shouldBe 2
            output.readText() shouldBe sample
        }

        test("skips the download when the existing size already matches the remote content length") {
            serve()
            val staleContent = "x".repeat(sample.length)
            val output = (root / "size-match").apply { writeText(staleContent) }

            Downloader(url, config = directories).download(output)

            requests shouldBe 1
            output.readText() shouldBe staleContent
        }

        test("falls through to downloading when the remote size cannot be determined") {
            handler = { exchange ->
                if (exchange.requestMethod == "HEAD") {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    val bytes = sample.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            val output = (root / "no-remote-size").apply { writeText("stale") }

            Downloader(url, config = directories).download(output)

            requests shouldBe 2
            output.readText() shouldBe sample
        }

        test("throws on an error status") {
            shouldThrow<ClientRequestException> { Downloader(url, config = directories).download(root / "not-found") }
        }

        test("leaves no output file when the request fails") {
            val output = root / "failed"

            shouldThrow<ClientRequestException> { Downloader(url, config = directories).download(output) }

            output.shouldNotExist()
        }

        test("keeps the previous output when the request fails") {
            val output = (root / "preserved").apply { writeText("previous") }

            shouldThrow<ClientRequestException> { Downloader(url, config = directories).download(output) }

            output.readText() shouldBe "previous"
        }

        test("removes the temporary file after a successful download") {
            serve()

            Downloader(url, config = directories).download(root / "clean-success")

            leftovers() shouldBe emptyList()
        }

        test("removes the temporary file after a failed download") {
            shouldThrow<ClientRequestException> { Downloader(url, config = directories).download(root / "clean-failure") }

            leftovers() shouldBe emptyList()
        }
    })
