package dev.klepto.utrecorder.common.io

import kotlinx.coroutines.runBlocking
import java.io.Closeable

/**
 * Shorthand for [use] that runs a suspending [block], blocking the calling thread until it completes.
 */
internal fun <T : Closeable?, R> T.useBlocking(block: suspend T.() -> R): R =
    runBlocking {
        use { block(it) }
    }
