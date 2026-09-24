package dev.klepto.utrecorder.core.media

import kotlin.time.Duration

/**
 * One progress report of a running media program: how far into its output the program has written.
 *
 * @property frames The number of video frames written so far.
 * @property time How much of the output's running time is written so far.
 */
data class MediaProgress(
    val frames: Long,
    val time: Duration,
)
