package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.PathName

/**
 * Represents a directory in the UT installation.
 *
 * @author Augustinas R. <http://github.com/klepto>
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
     * Represents the system directory.
     */
    object System : UtDirectory {
        override val pathName = "System"
        override val extensions = setOf("u", "ini", "exe", "so", "int", "dll")
    }

    /**
     * Represents the system400 directory.
     */
    object System400 : UtDirectory {
        override val pathName = "System400"
    }
}
