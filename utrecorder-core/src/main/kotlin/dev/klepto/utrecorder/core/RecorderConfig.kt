package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.SystemConfigReader
import dev.klepto.utrecorder.core.capture.audio.AudioCaptureConfig
import dev.klepto.utrecorder.core.capture.video.VideoCaptureConfig
import dev.klepto.utrecorder.ut99.UtConfig

/**
 * Settings a demo is recorded to mp4 with.
 *
 * Use the constructor. A property that is not provided takes the value its key configures, or else its literal
 * default. Without arguments, it reads the configuration of the current process.
 *
 * @param reader The source of the keys every nested configuration reads.
 * @property ut The Unreal Tournament installation demos are played in, which holds the compiled `UTRecorder.u` package.
 * @property video The settings of the video capture and of its encode.
 * @property audio The settings of the audio capture and of the audio of the mux.
 */
class RecorderConfig(
    reader: ConfigReader = SystemConfigReader(),
    val ut: UtConfig = UtConfig(reader),
    val video: VideoCaptureConfig = VideoCaptureConfig(reader),
    val audio: AudioCaptureConfig = AudioCaptureConfig(reader),
)
