package dev.klepto.utrecorder.core.demo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DemoPlaybackRenderDeviceTest :
    FunSpec({
        test("parse reads the configured name of each device regardless of case") {
            DemoPlaybackRenderDevice.parse("opengl") shouldBe DemoPlaybackRenderDevice.OPENGL
            DemoPlaybackRenderDevice.parse("XOpenGL") shouldBe DemoPlaybackRenderDevice.XOPENGL
        }

        test("parse rejects an unknown name") {
            shouldThrow<IllegalArgumentException> { DemoPlaybackRenderDevice.parse("d3d") }
        }
    })
