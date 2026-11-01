package dev.klepto.utrecorder.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.paths.shouldNotExist

class DiskSpaceTest :
    FunSpec({
        val root = tempdir().toPath()

        test("reads the usable bytes of the filesystem holding an existing directory") {
            DiskSpace.SYSTEM.usableBytes(root) shouldBeGreaterThan 0L
        }

        test("reads the usable bytes of the nearest existing ancestor of a path that does not exist") {
            val missing = root.resolve("recordings").resolve("nested")

            DiskSpace.SYSTEM.usableBytes(missing) shouldBeGreaterThan 0L
            missing.shouldNotExist()
        }
    })
