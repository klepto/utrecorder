package dev.klepto.utrecorder.cli

import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.core.RecorderStage
import dev.klepto.utrecorder.core.RecorderStage.Capturing
import dev.klepto.utrecorder.core.RecorderStage.Muxing
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Collections

class ProgressLogTest :
    FunSpec({
        test("log prints each stage and whole percent once, however often it is reported") {
            val printed = mutableListOf<Progress<RecorderStage>>()
            val progressLog = ProgressLog<RecorderStage> { printed += it }

            progressLog.log(Progress(Capturing, 0.421))
            progressLog.log(Progress(Capturing, 0.425))
            progressLog.log(Progress(Capturing, 1.0))
            progressLog.log(Progress(Capturing, 1.0))
            progressLog.log(Progress(Muxing, 1.0))
            progressLog.log(Progress(Muxing, 1.0))

            printed shouldBe
                listOf(
                    Progress(Capturing, 0.421),
                    Progress(Capturing, 1.0),
                    Progress(Muxing, 1.0),
                )
        }

        test("log prints a step once when many threads report it at the same time") {
            val printed = Collections.synchronizedList(mutableListOf<Progress<RecorderStage>>())
            val progressLog = ProgressLog<RecorderStage> { printed += it }

            coroutineScope {
                repeat(1000) {
                    launch(Dispatchers.Default) { progressLog.log(Progress(Capturing, 1.0)) }
                }
            }

            printed shouldBe listOf(Progress(Capturing, 1.0))
        }
    })
