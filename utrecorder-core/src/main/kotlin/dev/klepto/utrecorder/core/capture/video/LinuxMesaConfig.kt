package dev.klepto.utrecorder.core.capture.video

import dev.klepto.utrecorder.common.ConfigReader
import dev.klepto.utrecorder.common.SystemConfigReader

/**
 * Mesa tuning of the video capture on Linux: the llvmpipe software renderer and the GL dispatch.
 *
 * Use the constructor. A property that is not provided takes the value its `record.linux_mesa_*` key configures, or
 * else its literal default. Without arguments, it reads the configuration of the current process.
 *
 * @param reader The source of the `record.linux_mesa_*` keys.
 * @property threads The llvmpipe threads of the video capture, or `0` for the processor count.
 * @property vectorWidth The llvmpipe native vector width override, blank for Mesa's default.
 * @property glthread Whether Mesa's GL dispatch thread is enabled.
 * @property noError Whether Mesa skips GL error bookkeeping.
 */
class LinuxMesaConfig(
    reader: ConfigReader = SystemConfigReader(),
    val threads: Int = reader.get("record.linux_mesa_threads", 0, String::toInt),
    val vectorWidth: String = reader.get("record.linux_mesa_vector_width", ""),
    val glthread: Boolean = reader.get("record.linux_mesa_glthread", false, String::toBoolean),
    val noError: Boolean = reader.get("record.linux_mesa_no_error", false, String::toBoolean),
)
