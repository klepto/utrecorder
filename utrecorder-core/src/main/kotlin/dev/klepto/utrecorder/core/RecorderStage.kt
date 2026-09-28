package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.Progress
import dev.klepto.utrecorder.common.ProgressListener

/**
 * A step of a recording, in the order a recording runs them.
 */
enum class RecorderStage {
    /**
     * The demo is downloaded into the recording's workspace.
     */
    FetchingDemo,

    /**
     * The installation is provisioned, and the recorder's console package, HUD layout and maps are installed into it.
     */
    PreparingInstallation,

    /**
     * The packages the demo names are installed into the installation.
     */
    ResolvingPackages,

    /**
     * The video capture and the audio capture play the demo, and the frames are encoded.
     */
    Capturing,

    /**
     * The video and the audio are merged into the mp4.
     */
    Muxing,
    ;

    /**
     * Reports this stage to [onProgress] as started, and returns the listener that reports the progress of this stage
     * to [onProgress].
     *
     * The listener clamps the progress to the range from `0.0` to `1.0`, and reports progress that is not finite as
     * `0.0`.
     */
    fun start(onProgress: ProgressListener<RecorderStage>): ProgressListener<Unit> {
        val listener: ProgressListener<Unit> = { report -> onProgress(Progress(this, clamp(report.progress))) }
        return listener.apply { invoke(Progress(0.0)) }
    }

    private companion object {
        /**
         * Returns [progress] clamped to the range from `0.0` to `1.0`. If [progress] is not finite, returns `0.0`
         * instead.
         */
        fun clamp(progress: Double): Double =
            when {
                progress.isFinite() -> progress.coerceIn(0.0, 1.0)
                else -> 0.0
            }
    }
}
