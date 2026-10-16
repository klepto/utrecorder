package dev.klepto.utrecorder.core.demo

import kotlinx.coroutines.CancellationException

/**
 * Failure of a capture whose game reported that it cannot play the demo, so no later capture of the demo can succeed.
 *
 * Use [reason] to find such a report in one line of the game output.
 *
 * @param message The report of the game, as [reason] returns it.
 * @param cause The failure the capture ended with after the report, if any.
 */
class DemoUnplayableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    companion object {
        private val NATIVE_CLASS = Regex("""Failed to load native class (\S+)""")
        private const val CONNECTION_FAILED = "Browse: Index.unr?failed"

        /**
         * Returns what the game reports in [line], one line of its output: a native class it failed to load, or a
         * demo connection that failed. If [line] reports neither, returns `null` instead.
         */
        fun reason(line: String): String? =
            when {
                CONNECTION_FAILED in line -> "Demo connection failed"
                else -> NATIVE_CLASS.find(line)?.let { "Game failed to load native class ${it.groupValues[1]}" }
            }

        /**
         * Returns a [DemoUnplayableException] naming [reason], with [failure] as its cause. If [reason] is `null`, or
         * [failure] is a cancellation or a [DemoUnplayableException] itself, returns [failure] instead.
         */
        fun wrap(
            failure: Throwable,
            reason: String?,
        ): Throwable =
            when {
                reason == null || failure is CancellationException || failure is DemoUnplayableException -> failure
                else -> DemoUnplayableException(reason, failure)
            }
    }
}
