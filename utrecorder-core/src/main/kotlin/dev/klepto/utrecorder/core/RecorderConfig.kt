package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.ConfigReader
import dev.klepto.utrecorder.common.config.DirectoriesConfig
import dev.klepto.utrecorder.common.config.SystemConfigReader
import dev.klepto.utrecorder.common.io.get
import dev.klepto.utrecorder.common.io.toAbsolutePath
import dev.klepto.utrecorder.ut99.UtConfig
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration of a [UtRecorder]: the installation it plays demos in, the time limits of a run and the recording
 * settings the two passes and the mux are driven with.
 *
 * Use [DEFAULT] for the configuration the current process works against, or [fromProperties] to build one from a
 * given [ConfigReader].
 *
 * @property installationDirectory The Unreal Tournament installation demos are played in, holding the compiled
 * `UTRecorder.u` package.
 * @property startupTimeout How long after startup the game waits for a demo spectator before quitting, catching a bad
 * demo name. Written as `StartupTimeout`.
 * @property maxRunMargin How much longer than the demo itself a run may take, covering menu, level load and teardown.
 * Written as `MaxRunSeconds` together with the demo duration.
 * @property timeoutGrace How much longer than `MaxRunSeconds` a hung game is given before it is killed.
 * @property exitDelay How long the game keeps running after the demo hits its last frame. Written as `ExitDelay`.
 * @property fps The rendered frames per demo second the frame pass is locked to.
 * @property codec The ffmpeg video encoder the capture encodes into while the demo renders.
 * @property preset The encoder preset handed to codecs that take one, blank when the encoder has none.
 * @property crf The constant rate factor the encode targets, blank for an encode at an average [bitrate].
 * @property bitrate The video bitrate of the encode, in ffmpeg's notation: the ceiling of a [crf] encode, the average of
 * one without.
 * @property svtav1Params The `-svtav1-params` handed to the encoder, blank for none.
 * @property gop How many frames lie between the keyframes of the encode.
 * @property width The viewport width of the frame pass, also the width of the frames the shim sends.
 * @property height The viewport height of the frame pass, also the height of the frames the shim sends.
 * @property frameQueue The ffmpeg `thread_queue_size`, the frame-count half of the capture's backpressure bound.
 * @property framePort The loopback port the frame capture listens on, or `0` to take a free one.
 * @property frameTimeout How long the frame pass may run before it is killed, render- and encode-bound rather than
 * demo-bound.
 * @property recordArguments Extra arguments appended to the capture command line, split on spaces.
 * @property audio Whether the sound pass runs and the mux carries sound.
 * @property audioBitrate The audio bitrate of the mux, in ffmpeg's notation.
 * @property audioFilter The ffmpeg audio filter graph applied to the sound track in the mux.
 * @property audioOffset The manual nudge, in seconds, added to the audio placement.
 * @property soundRender Whether the sound pass draws frames; disabling it also collapses its marks.
 * @property soundWidth The viewport width of the sound pass.
 * @property soundHeight The viewport height of the sound pass.
 * @property soundFps The frame rate cap of the sound pass, which is also its console tick rate.
 * @property renderThreads The llvmpipe threads of the frame pass, or `0` for the processor count.
 * @property soundRenderThreads The llvmpipe threads of the sound pass.
 * @property renderVectorWidth The llvmpipe native vector width override, blank for Mesa's default.
 * @property renderGlthread Whether Mesa's GL dispatch thread is enabled.
 * @property renderNoError Whether Mesa skips GL error bookkeeping.
 */
data class RecorderConfig(
    val installationDirectory: Path,
    val startupTimeout: Duration = DEFAULT_STARTUP_TIMEOUT,
    val maxRunMargin: Duration = DEFAULT_MAX_RUN_MARGIN,
    val timeoutGrace: Duration = DEFAULT_TIMEOUT_GRACE,
    val exitDelay: Duration = DEFAULT_EXIT_DELAY,
    val fps: Int = DEFAULT_FPS,
    val codec: String = DEFAULT_CODEC,
    val preset: String = DEFAULT_PRESET,
    val crf: String = DEFAULT_CRF,
    val bitrate: String = DEFAULT_BITRATE,
    val svtav1Params: String = DEFAULT_SVTAV1_PARAMS,
    val gop: Int = DEFAULT_GOP,
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val frameQueue: Int = DEFAULT_FRAME_QUEUE,
    val framePort: Int = DEFAULT_FRAME_PORT,
    val frameTimeout: Duration = DEFAULT_FRAME_TIMEOUT,
    val recordArguments: String = DEFAULT_RECORD_ARGUMENTS,
    val audio: Boolean = DEFAULT_AUDIO,
    val audioBitrate: String = DEFAULT_AUDIO_BITRATE,
    val audioFilter: String = DEFAULT_AUDIO_FILTER,
    val audioOffset: Double = DEFAULT_AUDIO_OFFSET,
    val soundRender: Boolean = DEFAULT_SOUND_RENDER,
    val soundWidth: Int = DEFAULT_SOUND_WIDTH,
    val soundHeight: Int = DEFAULT_SOUND_HEIGHT,
    val soundFps: Int = DEFAULT_SOUND_FPS,
    val renderThreads: Int = DEFAULT_RENDER_THREADS,
    val soundRenderThreads: Int = DEFAULT_SOUND_RENDER_THREADS,
    val renderVectorWidth: String = DEFAULT_RENDER_VECTOR_WIDTH,
    val renderGlthread: Boolean = DEFAULT_RENDER_GLTHREAD,
    val renderNoError: Boolean = DEFAULT_RENDER_NO_ERROR,
) {
    /**
     * Returns the `MaxRunSeconds` a run playing a demo [demoDuration] long is configured with.
     */
    fun maxRunTime(demoDuration: Duration): Duration = demoDuration + maxRunMargin

    /**
     * Returns how long a run playing a demo [demoDuration] long may take before the game is killed.
     */
    fun timeout(demoDuration: Duration): Duration = maxRunTime(demoDuration) + timeoutGrace

    companion object {
        private val DEFAULT_STARTUP_TIMEOUT = 90.seconds
        private val DEFAULT_MAX_RUN_MARGIN = 120.seconds
        private val DEFAULT_TIMEOUT_GRACE = 60.seconds
        private val DEFAULT_EXIT_DELAY = 5.seconds
        private const val DEFAULT_FPS = 60
        private const val DEFAULT_CODEC = "libsvtav1"
        private const val DEFAULT_PRESET = "4"
        private const val DEFAULT_CRF = "28"
        private const val DEFAULT_BITRATE = "4800k"
        private const val DEFAULT_SVTAV1_PARAMS = "tune=0"
        private const val DEFAULT_GOP = 300
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
        private const val DEFAULT_FRAME_QUEUE = 32
        private const val DEFAULT_FRAME_PORT = 0
        private val DEFAULT_FRAME_TIMEOUT = 1800.seconds
        private const val DEFAULT_RECORD_ARGUMENTS = ""
        private const val DEFAULT_AUDIO = true
        private const val DEFAULT_AUDIO_BITRATE = "192k"
        private const val DEFAULT_AUDIO_FILTER = "aresample=async=1,loudnorm=I=-34:TP=-1.5:LRA=11"
        private const val DEFAULT_AUDIO_OFFSET = 0.0
        private const val DEFAULT_SOUND_RENDER = true
        private const val DEFAULT_SOUND_WIDTH = 64
        private const val DEFAULT_SOUND_HEIGHT = 64
        private const val DEFAULT_SOUND_FPS = 60
        private const val DEFAULT_RENDER_THREADS = 0
        private const val DEFAULT_SOUND_RENDER_THREADS = 1
        private const val DEFAULT_RENDER_VECTOR_WIDTH = ""
        private const val DEFAULT_RENDER_GLTHREAD = false
        private const val DEFAULT_RENDER_NO_ERROR = false

        /**
         * The configuration a caller that names none works against.
         */
        val DEFAULT: RecorderConfig by lazy { fromProperties() }

        /**
         * Returns the configuration [reader] holds, falling back to the Unreal Tournament installation under the
         * installs directory of [directories].
         */
        fun fromProperties(
            reader: ConfigReader = SystemConfigReader(),
            directories: DirectoriesConfig = DirectoriesConfig(),
        ): RecorderConfig =
            RecorderConfig(
                installationDirectory =
                    reader.get("core.installation_directory") { it.toAbsolutePath() }
                        ?: UtConfig(directoriesConfig = directories).gameDirectory,
                startupTimeout = reader.duration("core.startup_timeout") ?: DEFAULT_STARTUP_TIMEOUT,
                maxRunMargin = reader.duration("core.max_run_margin") ?: DEFAULT_MAX_RUN_MARGIN,
                timeoutGrace = reader.duration("core.timeout_grace") ?: DEFAULT_TIMEOUT_GRACE,
                exitDelay = reader.duration("core.exit_delay") ?: DEFAULT_EXIT_DELAY,
                fps = reader.get("record.fps", String::toInt) ?: DEFAULT_FPS,
                codec = reader["record.codec"] ?: DEFAULT_CODEC,
                preset = reader["record.preset"] ?: DEFAULT_PRESET,
                crf = reader["record.crf"] ?: DEFAULT_CRF,
                bitrate = reader["record.bitrate"] ?: DEFAULT_BITRATE,
                svtav1Params = reader["record.svtav1_params"] ?: DEFAULT_SVTAV1_PARAMS,
                gop = reader.get("record.gop", String::toInt) ?: DEFAULT_GOP,
                width = reader.get("record.width", String::toInt) ?: DEFAULT_WIDTH,
                height = reader.get("record.height", String::toInt) ?: DEFAULT_HEIGHT,
                frameQueue = reader.get("record.frame_queue", String::toInt) ?: DEFAULT_FRAME_QUEUE,
                framePort = reader.get("record.frame_port", String::toInt) ?: DEFAULT_FRAME_PORT,
                frameTimeout = reader.duration("record.frame_timeout") ?: DEFAULT_FRAME_TIMEOUT,
                recordArguments = reader["record.arguments"] ?: DEFAULT_RECORD_ARGUMENTS,
                audio = reader.get("record.audio", String::toBoolean) ?: DEFAULT_AUDIO,
                audioBitrate = reader["record.audio_bitrate"] ?: DEFAULT_AUDIO_BITRATE,
                audioFilter = reader["record.audio_filter"] ?: DEFAULT_AUDIO_FILTER,
                audioOffset = reader.get("record.audio_offset", String::toDouble) ?: DEFAULT_AUDIO_OFFSET,
                soundRender = reader.get("record.sound_render", String::toBoolean) ?: DEFAULT_SOUND_RENDER,
                soundWidth = reader.get("record.sound_width", String::toInt) ?: DEFAULT_SOUND_WIDTH,
                soundHeight = reader.get("record.sound_height", String::toInt) ?: DEFAULT_SOUND_HEIGHT,
                soundFps = reader.get("record.sound_fps", String::toInt) ?: DEFAULT_SOUND_FPS,
                renderThreads = reader.get("record.render_threads", String::toInt) ?: DEFAULT_RENDER_THREADS,
                soundRenderThreads =
                    reader.get("record.sound_render_threads", String::toInt) ?: DEFAULT_SOUND_RENDER_THREADS,
                renderVectorWidth = reader["record.render_vector_width"] ?: DEFAULT_RENDER_VECTOR_WIDTH,
                renderGlthread = reader.get("record.render_glthread", String::toBoolean) ?: DEFAULT_RENDER_GLTHREAD,
                renderNoError = reader.get("record.render_no_error", String::toBoolean) ?: DEFAULT_RENDER_NO_ERROR,
            )
    }
}
