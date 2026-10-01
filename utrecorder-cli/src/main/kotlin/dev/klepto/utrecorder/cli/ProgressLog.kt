package dev.klepto.utrecorder.cli

import dev.klepto.utrecorder.common.Progress
import java.util.concurrent.ConcurrentHashMap

/**
 * Log of the progress of a piece of work that passes each step to [print] once. A step is a state and a whole percent
 * of it.
 *
 * [log] can be called from any thread.
 *
 * @property print Receives the first progress report of each step.
 */
class ProgressLog<T>(
    private val print: (Progress<T>) -> Unit,
) {
    private val logged: MutableSet<Pair<T, Int>> = ConcurrentHashMap.newKeySet()

    /**
     * Passes [progress] to [print] if no report of its step was logged before.
     */
    fun log(progress: Progress<T>) {
        if (logged.add(progress.state to (progress.progress * 100).toInt())) print(progress)
    }
}
