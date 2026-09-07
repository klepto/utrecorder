package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.PathName

/**
 * A directory of a UT installation, resolved against the installation root with the `get` operator.
 */
sealed interface UtDirectory : PathName {
    /**
     * The directory name.
     */
    val name: String
        get() = pathName

    /**
     * The directory extensions.
     */
    val extensions: Set<String>
        get() = emptySet()

    /**
     * The directory holding the executables, the configuration and the compiled packages.
     */
    object System : UtDirectory {
        override val pathName = "System"
        override val extensions = setOf("u", "ini", "exe", "so", "int", "dll")
    }

    /**
     * The directory the original ISO ships its 400-series system files in, merged into [System] on install.
     */
    object System400 : UtDirectory {
        override val pathName = "System400"
    }
}
