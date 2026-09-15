package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.move
import dev.klepto.utrecorder.common.time.TimestampId
import dev.klepto.utrecorder.ut99.UtInstallation
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists

/**
 * One recording of a demo: the staged demo, the two passes and the mux, run inside a workspace, finishing at the mp4
 * in the output directory.
 *
 * Reached through [UtRecorder.record]; one recording per installation at a time.
 *
 * @property config The recording the passes and the mux are driven with.
 * @property installation The installation both passes run in.
 * @property directories The directories the recording caches its demo in and writes its mp4 to.
 * @property ids The source of the job id the mp4 lands under.
 * @property ffmpeg The ffmpeg the video length is read against and the capture finalises on.
 * @property framePass The pass playing the demo through the shim.
 * @property soundPass The pass playing the demo with sound on.
 * @property muxer The mux putting the two outputs together into the mp4.
 */
class RecordingSession(
    private val config: RecorderConfig,
    private val installation: UtInstallation,
    private val directories: Directories = Directories.DEFAULT,
    private val ids: TimestampId = TimestampId(),
    private val ffmpeg: FfmpegLike = Ffmpeg(),
    framePass: FramePassLike? = null,
    soundPass: SoundPassLike? = null,
    muxer: MuxerLike? = null,
) {
    /**
     * The hook artifacts and the wave configuration provisioned for this recording.
     */
    private val provision = RecordingProvision(installation, config)

    /**
     * The frame pass this recording plays, the session's own hook-provisioned one when none was handed in.
     */
    private val framePass: FramePassLike = framePass ?: FramePass(config, installation, provision.hooks(), directories)

    /**
     * The sound pass this recording plays, the session's own wave-provisioned one when none was handed in.
     */
    private val soundPass: SoundPassLike = soundPass ?: SoundPass(config, installation, provision.openAl(), directories)

    /**
     * The mux this recording merges through, the configured one when none was handed in.
     */
    private val muxer: MuxerLike = muxer ?: Muxer(config)

    /**
     * Runs both passes against [demo], muxes their outputs into the mp4 named [name] and returns its path under the
     * output directory, the mp4 moved there before the [workspace] closes.
     *
     * The sound pass runs on a daemon thread started ahead of the frame pass and joined before the capture stops; a
     * failed sound pass fails the recording, never leaving it the video alone. The mux reads the video length back
     * off the finished capture, places the sound track on the frame pass's end mark, and the finished mp4 is moved
     * out to `output/<job-id>/<name>.mp4`, the wav deleted behind it, so nothing the session produced lingers in the
     * workspace on a returned success. The workspace's failure-keeping diagnostics survive a throw.
     *
     * @throws IllegalStateException when the frame pass or, when [RecorderConfig.audio] holds, the sound pass fails.
     */
    fun record(
        demo: Path,
        name: String,
        workspace: Workspace,
    ): Path {
        val outcome = SoundOutcome()

        val soundThread =
            when (config.audio) {
                true -> {
                    Thread {
                        try {
                            outcome.track = soundPass.record(demo, workspace)
                        } catch (failure: IllegalStateException) {
                            outcome.failure = failure
                        }
                    }.apply {
                        isDaemon = true
                        this.name = SOUND_THREAD
                    }
                }

                else -> {
                    null
                }
            }

        val recorder = FrameRecorder(config, workspace.file(CAPTURE_NAME), ffmpeg)
        soundThread?.start()
        val marks = framePass.record(demo, recorder, workspace)
        soundThread?.join()
        val track = outcome.track
        val video = recorder.stop()
        outcome.failure?.let { throw it }
        val placedTrack =
            track?.let { placed ->
                val seconds = marks.end.demoTime.inWholeSeconds
                val demoEnd = seconds.toDouble()
                val placement = SoundPlacement.place(placed, ffmpeg.duration(video), demoEnd, config)
                placed.copy(offset = placement.offset, length = placement.length)
            }
        val target = directories.output[ids.next()][name + MP4_EXTENSION]
        target.parent.createDirectories()
        val required = checkNotNull(placedTrack) { "The sound pass of $video produced no track" }
        muxer.merge(video, required, target)
        track?.file?.deleteIfExists()
        return target
    }

    /**
     * The sound track the daemon thread produced, the session's join ordering keeping its write and this read apart.
     */
    private class SoundOutcome {
        @Volatile
        var track: SoundTrack? = null

        @Volatile
        var failure: IllegalStateException? = null
    }

    companion object {
        /**
         * The name the daemon thread of the sound pass carries.
         */
        private const val SOUND_THREAD = "sound-pass"

        /**
         * The name the frame capture is written under inside the workspace.
         */
        private const val CAPTURE_NAME = "capture.mp4"

        /**
         * The extension the finished mp4 carries.
         */
        private const val MP4_EXTENSION = ".mp4"
    }
}
