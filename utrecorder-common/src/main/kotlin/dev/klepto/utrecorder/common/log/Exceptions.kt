package dev.klepto.utrecorder.common.log

/**
 * Throws the first of these failures with every other one added to it as suppressed. Does nothing when there are none.
 */
fun List<Throwable>.mergeAndThrow() {
    firstOrNull()?.let { first ->
        drop(1).forEach(first::addSuppressed)
        throw first
    }
}
