package dev.klepto.utrecorder.common

import dev.klepto.utrecorder.common.http.DownloadListener

/**
 * A callback receiving how much of a piece of work is done, from `0.0` to `1.0`.
 */
typealias ProgressListener = (progress: Double) -> Unit

/**
 * Returns a listener for one part of the work this listener tracks, the part running from [start] to [end] of the
 * whole. The progress of the part is reported to this listener as the matching progress of the whole.
 */
fun ProgressListener.part(
    start: Double,
    end: Double,
): ProgressListener = { progress -> this(start + (end - start) * progress.coerceIn(0.0, 1.0)) }

/**
 * Returns a download listener that reports the bytes received out of the total size to this listener. While the total
 * size is unknown, reports nothing.
 */
fun ProgressListener.asDownloadListener(): DownloadListener = { received, total -> if (total > 0) this(received.toDouble() / total) }

/**
 * Returns [count] listeners for parts of the work this listener tracks that run side by side. Each report of a part
 * reports the least progress of all parts to this listener. A part that has not reported yet counts as `0.0`.
 */
fun ProgressListener.slowest(count: Int): List<ProgressListener> {
    val progress = DoubleArray(count)
    return List(count) { index ->
        { value ->
            synchronized(progress) {
                progress[index] = value
                this(progress.min())
            }
        }
    }
}
