package dev.klepto.utrecorder.common

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Throws the first throwable of this list, with every other throwable added to it as suppressed.
 *
 * If the list is empty, does nothing instead.
 */
fun List<Throwable>.mergeAndThrow() {
    firstOrNull()?.let { first ->
        drop(1).forEach(first::addSuppressed)
        throw first
    }
}

/**
 * Returns the outcome of [block] as a [Result], holding any throwable it throws as a failure.
 *
 * A [CancellationException] is not held. It propagates instead.
 */
inline fun <T> suspendCatching(block: () -> T): Result<T> = runCatching(block).onFailure { if (it is CancellationException) throw it }

/**
 * Returns the outcome of [block] as a [Result], holding an [IOException] it throws as a failure.
 *
 * Every other throwable, a [CancellationException] included, propagates instead.
 */
inline fun <T> suspendCatchingIo(block: () -> T): Result<T> = suspendCatching(block).onFailure { if (it !is IOException) throw it }
