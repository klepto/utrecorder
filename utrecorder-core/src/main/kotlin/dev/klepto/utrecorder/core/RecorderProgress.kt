package dev.klepto.utrecorder.core

/**
 * One progress report of a recording: the stage that runs and an estimate of how much of it is done.
 *
 * @property stage The stage that runs.
 * @property progress How much of [stage] is done, from `0.0` to `1.0`.
 */
data class RecorderProgress(
    val stage: RecorderStage,
    val progress: Double,
)
