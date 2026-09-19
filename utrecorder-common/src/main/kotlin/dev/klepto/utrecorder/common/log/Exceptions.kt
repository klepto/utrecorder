package dev.klepto.utrecorder.common.log

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Throws the first of these failures with every other one added to it as suppressed. Does nothing when there are none.
 */
fun List<Throwable>.mergeAndThrow() {
    firstOrNull()?.let { first ->
        drop(1).forEach(first::addSuppressed)
        throw first
    }
}

/**
 * Runs [block] and returns its outcome as a [Result], holding any [Throwable] it throws as a failure and rethrowing a
 * [CancellationException] instead of holding it.
 */
inline fun <T> catching(block: () -> T): Result<T> = runCatching(block).onFailure { if (it is CancellationException) throw it }

/**
 * Runs [block] and returns its outcome, holding an [IOException] it throws as a failure and letting everything else,
 * a [CancellationException] included, propagate.
 */
inline fun <T> catchingIo(block: () -> T): Result<T> =
    catching(block).also { result -> result.exceptionOrNull()?.let { if (it !is IOException) throw it } }
