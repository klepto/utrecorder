package dev.klepto.utrecorder.core.capture

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

/**
 * Returns the result of [block], cancelling it once [progress] has not changed for [idle]. The first idle window starts
 * when [block] starts, and every new value of [progress] starts a new one. A value equal to the current one is not
 * progress.
 *
 * @throws TimeoutException if [progress] does not change for [idle] before [block] returns.
 */
suspend fun <T> withProgressTimeout(
    progress: StateFlow<Long>,
    idle: Duration,
    block: suspend () -> T,
): T =
    coroutineScope {
        val watchdog =
            launch {
                progress.collectLatest {
                    delay(idle)
                    throw TimeoutException("No progress for $idle, last progress $it")
                }
            }
        block().also { watchdog.cancel() }
    }
