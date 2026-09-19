package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.common.io.Workspace
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.time.TimestampId
import dev.klepto.utrecorder.ut99.UtInstallation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.time.DurationUnit

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
 * @param framePass The frame pass to use instead of building one, or `null` to build one in [record].
 * @param soundPass The sound pass to use instead of building one, or `null` to build one in [record].
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
     * The frame pass handed in for this recording, used by [record] instead of building one; `null` when none was
     * handed in, so [record] builds one there instead of here, since provisioning is disk IO and this constructor is
     * not suspend.
     */
    private val framePassOverride: FramePassLike? = framePass

    /**
     * The sound pass handed in for this recording, used by [record] instead of building one; `null` for the same
     * reason and with the same handling as [framePassOverride].
     */
    private val soundPassOverride: SoundPassLike? = soundPass

    /**
     * The mux this recording merges through, the configured one when none was handed in.
     */
    private val muxer: MuxerLike = muxer ?: Muxer(config)

    /**
     * Runs both passes against [demo], muxes their outputs into the mp4 named [name] and returns its path under the
     * output directory, the mp4 moved there before the [workspace] closes.
     *
     * The sound pass runs as an `async(`[Dispatchers.IO]`)` child of this call's own `coroutineScope`, started ahead
     * of the frame pass and awaited before the capture stops; a failed sound pass fails the recording, never leaving
     * it the video alone, and cancelling the coroutine running this call cancels both passes in turn. The mux reads
     * the video length back off the finished capture, places the sound track on the frame pass's end mark, and the
     * finished mp4 is moved out to `output/<job-id>/<name>.mp4`, the wav deleted behind it, so nothing the session produced
     * lingers in the workspace on a returned success. The workspace's failure-keeping diagnostics survive a throw.
     *
     * @throws IllegalStateException when the frame pass or, when [RecorderConfig.audio] holds, the sound pass fails.
     */
    suspend fun record(
        demo: Path,
        name: String,
        workspace: Workspace,
    ): Path {
        val framePass = framePassOverride ?: FramePass(config, installation, provision.hooks(), directories)
        val soundPass = soundPassOverride ?: SoundPass(config, installation, provision.openAl(), directories)
        val recorder = FrameRecorder(config, workspace.file(CAPTURE_NAME), ffmpeg)

        val (marks, track) =
            coroutineScope {
                // Dispatched explicitly, not inherited: `coroutineScope` starts an `async` child undispatched on the
                // calling thread until it first suspends, so on a single-threaded blocking-event-loop caller (a test
                // driven through Kotlin's blocking coroutine builder) an undispatched sound pass would never get a
                // turn to run against a frame pass that blocks the same thread before its own first suspension
                // point, starving the sound pass forever.
                val soundJob = if (config.audio) async(Dispatchers.IO) { soundPass.record(demo, workspace) } else null
                var placedTrack: SoundTrack? = null
                val recordedMarks = framePass.record(demo, recorder, workspace) { placedTrack = soundJob?.await() }
                recordedMarks to placedTrack
            }

        val video = recorder.target
        val placedTrack =
            track?.let { placed ->
                val demoEnd = marks.end.demoTime.toDouble(DurationUnit.SECONDS)
                val placement = SoundPlacement.place(placed, ffmpeg.duration(video), demoEnd, config)
                placed.copy(offset = placement.offset, length = placement.length)
            }
        val target = directories.output[ids.next()][name + MP4_EXTENSION]
        withContext(Dispatchers.IO) { target.parent.createDirectories() }
        val required = checkNotNull(placedTrack) { "The sound pass of $video produced no track" }
        muxer.merge(video, required, target)
        withContext(Dispatchers.IO) { track?.file?.deleteIfExists() }
        return target
    }

    companion object {
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
