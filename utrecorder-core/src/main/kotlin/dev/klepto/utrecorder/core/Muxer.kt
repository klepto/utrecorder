package dev.klepto.utrecorder.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * The mux of a recording: ffmpeg placing a [SoundTrack] against the finished frame capture and writing the mp4.
 *
 * @property config The recording the mux runs for, carrying the audio filter and bitrate.
 * @property ffmpeg The ffmpeg the mux process is spawned from.
 */
class Muxer(
    private val config: RecorderConfig,
    private val ffmpeg: Ffmpeg = Ffmpeg(),
) : MuxerLike {
    /**
     * Merges [video] with [track] into [target] and returns [target], the video stream copied and the audio placed.
     *
     * @throws IllegalStateException when ffmpeg fails.
     */
    override suspend fun merge(
        video: Path,
        track: SoundTrack,
        target: Path,
    ): Path {
        withContext(Dispatchers.IO) { target.toAbsolutePath().toFile().delete() }
        if (ffmpeg.run(*arguments(video, track, target).toTypedArray())) {
            return target
        }
        throw IllegalStateException("Merging ${video.fileName} with ${track.file.fileName} failed")
    }

    /**
     * The arguments the mux runs ffmpeg with, trimming the audio to where [track] places it against the video.
     */
    fun arguments(
        video: Path,
        track: SoundTrack,
        target: Path,
    ): List<String> =
        listOf(
            "-y",
            "-i",
            video.toString(),
            "-ss",
            seconds(track.offset),
            "-t",
            seconds(track.length),
            "-i",
            track.file.toString(),
            "-map",
            "0:v:0",
            "-map",
            "1:a:0",
            "-c:v",
            "copy",
            "-af",
            config.audioFilter,
            "-c:a",
            "aac",
            "-b:a",
            config.audioBitrate,
            "-ac",
            "2",
            "-shortest",
            "-movflags",
            "+faststart",
            target.toString(),
        )

    private fun seconds(value: Double): String = "%.3f".format(value)
}
