package dev.klepto.utrecorder.common

/**
 * One progress report of a piece of work: the state the work is in and an estimate of how much of that state is done.
 *
 * Work that has no state of its own reports `Progress(progress)`, a report with the [Unit] state.
 *
 * @property state The state the work is in.
 * @property progress How much of [state] is done, from `0.0` to `1.0`.
 */
data class Progress<T>(
    val state: T,
    val progress: Double,
) {
    companion object {
        /**
         * Returns a progress report of work that has no state of its own.
         */
        operator fun invoke(progress: Double): Progress<Unit> = Progress(Unit, progress)
    }
}

/**
 * A callback receiving progress reports of a piece of work.
 */
typealias ProgressListener<T> = (progress: Progress<T>) -> Unit

/**
 * Returns a listener for one part of the work this listener tracks, the part running from [start] to [end] of the
 * whole. The progress of the part is reported to this listener as the matching progress of the whole, with the state
 * the part reports.
 */
fun <T> ProgressListener<T>.part(
    start: Double,
    end: Double,
): ProgressListener<T> = { report -> this(report.copy(progress = start + (end - start) * report.progress.coerceIn(0.0, 1.0))) }

/**
 * Returns [count] listeners for parts of the work this listener tracks that run side by side. Each report of a part
 * reports the least progress of all parts to this listener, with the state of that report. A part that has not
 * reported yet counts as `0.0`.
 */
fun <T> ProgressListener<T>.slowest(count: Int): List<ProgressListener<T>> {
    val progress = DoubleArray(count)
    return List(count) { index ->
        { report ->
            synchronized(progress) {
                progress[index] = report.progress
                this(report.copy(progress = progress.min()))
            }
        }
    }
}
