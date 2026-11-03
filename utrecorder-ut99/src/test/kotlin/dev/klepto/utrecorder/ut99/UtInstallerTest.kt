package dev.klepto.utrecorder.ut99

import com.sun.net.httpserver.HttpServer
import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.DirectoriesConfig
import dev.klepto.utrecorder.common.http.HashMismatchException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * Tests the base manifest that [UtInstaller] writes, against a stand-in iso and patch served by a local HTTP server.
 *
 * The stand-in iso is a minimal ISO 9660 image written by this spec, and the patch is a zip or a bzip2-compressed tar,
 * whichever the current platform installs. The server records every request it gets.
 */
@OptIn(ExperimentalPathApi::class)
class UtInstallerTest :
    FunSpec({
        val root = createTempDirectory("utrecorder-utinstaller-test")
        val patchFileName = UtPlatform.current().patchFileName("469e")
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val served = mutableMapOf<String, ByteArray>()
        val requests = mutableListOf<String>()

        server.createContext("/") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI.rawPath}"
            val body = served[exchange.requestURI.rawPath]
            try {
                when {
                    body == null -> {
                        exchange.sendResponseHeaders(404, -1)
                    }

                    exchange.requestMethod == "HEAD" -> {
                        exchange.responseHeaders.set("Content-Length", "${body.size}")
                        exchange.sendResponseHeaders(200, -1)
                    }

                    else -> {
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                }
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
            served.clear()
            requests.clear()
        }

        val address = "http://${server.address.hostString}:${server.address.port}"
        val isoRequest = "GET /ut.iso"
        val patchRequest = "GET /v469e/$patchFileName"

        fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * Returns a minimal ISO 9660 image holding [files], each by its path with `/` as separator.
         */
        fun iso(files: Map<String, ByteArray>): ByteArray {
            val sectorSize = 2048
            val directories = sortedSetOf("")
            files.keys.forEach { path ->
                generateSequence(path.substringBeforeLast('/', "")) {
                    it
                        .takeIf { parent ->
                            parent.isNotEmpty()
                        }?.substringBeforeLast('/', "")
                }.forEach { directories += it }
            }
            val directoryExtents = directories.withIndex().associate { (index, directory) -> directory to 18 + index }
            var nextSector = 18 + directories.size
            val fileExtents =
                files.mapValues { (_, content) ->
                    nextSector.also { nextSector += maxOf(1, (content.size + sectorSize - 1) / sectorSize) }
                }
            val image = ByteBuffer.allocate(nextSector * sectorSize).order(ByteOrder.LITTLE_ENDIAN)

            fun both32(
                offset: Int,
                value: Int,
            ) {
                image.order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
                image.order(ByteOrder.BIG_ENDIAN).putInt(offset + 4, value)
            }

            fun both16(
                offset: Int,
                value: Int,
            ) {
                image.order(ByteOrder.LITTLE_ENDIAN).putShort(offset, value.toShort())
                image.order(ByteOrder.BIG_ENDIAN).putShort(offset + 2, value.toShort())
            }

            fun record(
                offset: Int,
                name: ByteArray,
                extent: Int,
                size: Int,
                directory: Boolean,
            ): Int {
                val length = 33 + name.size + (if (name.size % 2 == 0) 1 else 0)
                image.put(offset, length.toByte())
                both32(offset + 2, extent)
                both32(offset + 10, size)
                image.put(offset + 18, 100.toByte())
                image.put(offset + 19, 1.toByte())
                image.put(offset + 20, 1.toByte())
                image.put(offset + 25, (if (directory) 2 else 0).toByte())
                both16(offset + 28, 1)
                image.put(offset + 32, name.size.toByte())
                name.forEachIndexed { index, byte -> image.put(offset + 33 + index, byte) }
                return offset + length
            }

            val descriptor = 16 * sectorSize
            image.put(descriptor, 1.toByte())
            "CD001".toByteArray().forEachIndexed { index, byte -> image.put(descriptor + 1 + index, byte) }
            image.put(descriptor + 6, 1.toByte())
            both32(descriptor + 80, nextSector)
            both16(descriptor + 128, sectorSize)
            listOf(813, 830, 847, 864).forEach { date -> repeat(16) { image.put(descriptor + date + it, '0'.code.toByte()) } }
            record(descriptor + 156, byteArrayOf(0), directoryExtents.getValue(""), sectorSize, directory = true)
            val terminator = 17 * sectorSize
            image.put(terminator, 255.toByte())
            "CD001".toByteArray().forEachIndexed { index, byte -> image.put(terminator + 1 + index, byte) }
            image.put(terminator + 6, 1.toByte())

            directories.forEach { directory ->
                val extent = directoryExtents.getValue(directory)
                var offset = extent * sectorSize
                offset = record(offset, byteArrayOf(0), extent, sectorSize, directory = true)
                offset = record(offset, byteArrayOf(1), extent, sectorSize, directory = true)

                fun isChild(path: String) = path.substringBeforeLast('/', "") == directory && path != directory
                directories.filter(::isChild).forEach { child ->
                    offset = record(offset, child.substringAfterLast('/').toByteArray(), directoryExtents.getValue(child), sectorSize, true)
                }
                files.filterKeys(::isChild).forEach { (path, content) ->
                    offset = record(offset, path.substringAfterLast('/').toByteArray(), fileExtents.getValue(path), content.size, false)
                }
            }
            files.forEach { (path, content) ->
                content.forEachIndexed { index, byte -> image.put(fileExtents.getValue(path) * sectorSize + index, byte) }
            }
            return image.array()
        }

        /**
         * Returns a patch archive holding [files], in the format of [patchFileName].
         */
        fun patch(files: Map<String, ByteArray>): ByteArray {
            val archive = root / "build-$patchFileName"
            when {
                patchFileName.endsWith(".zip") -> {
                    ZipArchiveOutputStream(archive.outputStream()).use { zip ->
                        files.forEach { (path, content) ->
                            zip.putArchiveEntry(ZipArchiveEntry(path))
                            zip.write(content)
                            zip.closeArchiveEntry()
                        }
                    }
                }

                else -> {
                    TarArchiveOutputStream(BZip2CompressorOutputStream(archive.outputStream())).use { tar ->
                        files.forEach { (path, content) ->
                            tar.putArchiveEntry(TarArchiveEntry(path).apply { size = content.size.toLong() })
                            tar.write(content)
                            tar.closeArchiveEntry()
                        }
                    }
                }
            }
            return archive.readBytes().also { archive.deleteExisting() }
        }

        val isoFiles =
            mapOf(
                "System/Engine.u" to "iso engine".toByteArray(),
                "System400/Core.u" to "iso core".toByteArray(),
                "System400/Shared.u" to "iso shared".toByteArray(),
                "Maps/CTF-Face.unr.uz" to "iso face".toByteArray(),
                "Help/ReadMe.txt" to "iso help".toByteArray(),
                "Setup/Setup.exe" to "iso setup".toByteArray(),
            )
        val patchFiles =
            mapOf(
                "System/Engine.u" to "patch engine".toByteArray(),
                "System/Shared.u" to "patch shared".toByteArray(),
                "System/Patch.dll" to "patch dll".toByteArray(),
                "ReleaseNotes.md" to "patch notes".toByteArray(),
            )
        val expectedManifest =
            listOf(
                "Help/ReadMe.txt",
                "Maps/CTF-Face.unr.uz",
                "ReleaseNotes.md",
                "System/Core.u",
                "System/Engine.u",
                "System/Patch.dll",
                "System/Shared.u",
            )

        /**
         * Returns a configuration over a home of its own named [name], whose sources are the local server serving [iso]
         * and [patch].
         */
        fun configOf(
            name: String,
            iso: ByteArray = iso(isoFiles),
            patch: ByteArray = patch(patchFiles),
        ): UtConfig {
            served["/ut.iso"] = iso
            served["/v469e/$patchFileName"] = patch
            val home = root / name
            return UtConfig(
                ConfigReader.EMPTY,
                DirectoriesConfig(ConfigReader.EMPTY, home = home),
                gameDirectory = home / "game",
                isoUrl = "$address/ut.iso",
                isoMd5 = md5(iso),
                patchUrl = address,
                patchVersion = "469e",
            )
        }

        /**
         * Returns the content and modification time of every entry of [directory] other than the base manifest, by path.
         */
        fun snapshot(directory: Path): Map<String, Pair<String, Any>> =
            directory
                .walk()
                .filter { it.isRegularFile() && it.fileName.toString() != ".utrecorder.base" }
                .associate { directory.relativize(it).invariantSeparatorsPathString to (it.readText() to it.getLastModifiedTime()) }

        /**
         * Returns an installed game in [config] with no manifest: the version file and two files, one of them a
         * fetched package. Also places the iso and the patch the server serves in the cache.
         */
        fun installedWithoutManifest(config: UtConfig) {
            val game = config.gameDirectory
            (game / ".utrecorder.version").createParentDirectories().writeText(config.patchFileName)
            (game / "System" / "Engine.u").createParentDirectories().writeText("installed engine")
            (game / "Maps" / "Fetched.unr").createParentDirectories().writeText("fetched")
            config.directoriesConfig.cache.createDirectories()
            (config.directoriesConfig.cache / "ut99.iso").writeBytes(served.getValue("/ut.iso"))
            (config.directoriesConfig.cache / config.patchFileName).writeBytes(served.getValue("/v469e/$patchFileName"))
        }

        context("fresh install") {
            test("lists every extracted file at its final path, one per line") {
                val config = configOf("fresh")

                UtInstaller(config).install {}

                val manifest = (config.gameDirectory / ".utrecorder.base").readText()
                manifest shouldBe expectedManifest.joinToString("") { "$it\n" }
            }

            test("lists System400 files under System") {
                val config = configOf("system400")

                UtInstaller(config).install {}

                val lines = (config.gameDirectory / ".utrecorder.base").readText().lines()
                lines.filter { it.startsWith("System400/") }.shouldBeEmpty()
                (config.gameDirectory / "System" / "Core.u").readText() shouldBe "iso core"
            }

            test("lists a file that both the iso and the patch hold once") {
                val config = configOf("overlap")

                UtInstaller(config).install {}

                val lines = (config.gameDirectory / ".utrecorder.base").readText().lines()
                lines.count { it == "System/Engine.u" } shouldBe 1
                lines.count { it == "System/Shared.u" } shouldBe 1
            }

            test("lists each file by the name it has on disk when the iso and the patch differ in case") {
                val config =
                    configOf(
                        "case",
                        iso = iso(mapOf("System/botpack.u" to "iso".toByteArray())),
                        patch = patch(mapOf("System/BotPack.u" to "patch".toByteArray())),
                    )

                UtInstaller(config).install {}

                val lines = (config.gameDirectory / ".utrecorder.base").readText().lines().dropLast(1)
                val onDisk =
                    (config.gameDirectory / "System")
                        .walk()
                        .map { config.gameDirectory.relativize(it).invariantSeparatorsPathString }
                        .toList()
                lines shouldContainExactlyInAnyOrder onDisk
            }
        }

        context("installed game without a manifest") {
            test("writes the manifest from the cached archives without requesting either") {
                val config = configOf("listed")
                installedWithoutManifest(config)

                UtInstaller(config).install {}

                (config.gameDirectory / ".utrecorder.base").readText() shouldBe expectedManifest.joinToString("") { "$it\n" }
                requests.filter { it.startsWith("GET") }.shouldBeEmpty()
            }

            test("extracts nothing and changes no other file of the installation") {
                val config = configOf("untouched")
                installedWithoutManifest(config)
                val before = snapshot(config.gameDirectory)

                UtInstaller(config).install {}

                snapshot(config.gameDirectory) shouldBe before
            }

            test("downloads the archives the cache no longer holds") {
                val config = configOf("redownload")
                installedWithoutManifest(config)
                (config.directoriesConfig.cache / "ut99.iso").deleteExisting()
                (config.directoriesConfig.cache / config.patchFileName).deleteExisting()

                UtInstaller(config).install {}

                requests.filter { it.startsWith("GET") } shouldContainExactlyInAnyOrder listOf(isoRequest, patchRequest)
                (config.gameDirectory / ".utrecorder.base").readText() shouldBe expectedManifest.joinToString("") { "$it\n" }
            }

            test("fails without a manifest when the downloaded iso does not match the checksum") {
                val config = configOf("mismatch")
                installedWithoutManifest(config)
                (config.directoriesConfig.cache / "ut99.iso").deleteExisting()
                served["/ut.iso"] = iso(mapOf("System/Other.u" to "other".toByteArray()))

                shouldThrow<HashMismatchException> { UtInstaller(config).install {} }

                (config.gameDirectory / ".utrecorder.base").exists() shouldBe false
            }
        }

        context("installed game with a manifest") {
            test("leaves the manifest unchanged and requests nothing") {
                val config = configOf("kept")
                installedWithoutManifest(config)
                (config.gameDirectory / ".utrecorder.base").writeText("System/Kept.u\n")

                UtInstaller(config).install {}

                (config.gameDirectory / ".utrecorder.base").readText() shouldBe "System/Kept.u\n"
                requests.shouldBeEmpty()
            }
        }

        test("uninstall deletes the manifest with the game directory") {
            val config = configOf("uninstall")
            UtInstaller(config).install {}

            UtInstaller(config).uninstall()

            (config.gameDirectory / ".utrecorder.base").exists() shouldBe false
        }
    })
