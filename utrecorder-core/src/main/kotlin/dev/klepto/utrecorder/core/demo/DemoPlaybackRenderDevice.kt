package dev.klepto.utrecorder.core.demo

/**
 * Render device a demo playback renders with.
 *
 * @property section The class of the render device, which is also the name of its game ini section.
 */
enum class DemoPlaybackRenderDevice(
    val section: String,
) {
    /** `OpenGLDrv`, the OpenGL renderer of Unreal Tournament 469. */
    OPENGL("OpenGLDrv.OpenGLRenderDevice"),

    /** `XOpenGLDrv`, the OpenGL 4.5 core renderer of Unreal Tournament 469. */
    XOPENGL("XOpenGLDrv.XOpenGLRenderDevice"),
    ;

    companion object {
        /**
         * Returns the render device a configured [name], `opengl` or `xopengl` in any case, stands for.
         *
         * @throws IllegalArgumentException when [name] names no render device.
         */
        fun parse(name: String): DemoPlaybackRenderDevice =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown render device '$name', expected opengl or xopengl")
    }
}
