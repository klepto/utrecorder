package dev.klepto.utrecorder.core

import dev.klepto.utrecorder.common.config.Directories
import dev.klepto.utrecorder.ut99.UtInstallation
import java.nio.file.Path

/**
 * The sound pass of a recording: a UT99 run at real speed with sound on, writing a wav through OpenAL's wave output,
 * whose placement the demo marks of its own log settle.
 *
 * @property config The recording the pass runs for, carrying the sound-pass viewport, its frame rate cap and the
 * audio toggle.
 * @property installation The installation the pass runs in.
 * @property directories The directories the pass stages its demo in and reads its output from.
 */
class SoundPass(
    private val config: RecorderConfig,
    private val installation: UtInstallation,
    private val directories: Directories = Directories.DEFAULT,
) {
    /**
     * Plays [demo] with sound on and returns the [SoundTrack] the pass produced, placed by its own marks.
     *
     * @throws IllegalStateException when the pass produces no wav, no marks, or marks that fall outside its wav.
     */
    fun record(demo: Path): SoundTrack = TODO()
}
