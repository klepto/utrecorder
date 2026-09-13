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
     * The extension of the packages this directory holds, or `null` when it holds no packages. It decides which files
     * of the directory are packages and which directory a fetched package is written to, and nothing else.
     */
    val extension: String?
        get() = null

    /**
     * The directory holding the executables, the configuration and the compiled packages.
     */
    object System : UtDirectory {
        override val pathName = "System"
        override val extension = "u"
    }

    /**
     * The directory holding the maps.
     */
    object Maps : UtDirectory {
        override val pathName = "Maps"
        override val extension = "unr"
    }

    /**
     * The directory holding the textures.
     */
    object Textures : UtDirectory {
        override val pathName = "Textures"
        override val extension = "utx"
    }

    /**
     * The directory holding the sounds.
     */
    object Sounds : UtDirectory {
        override val pathName = "Sounds"
        override val extension = "uax"
    }

    /**
     * The directory holding the music.
     */
    object Music : UtDirectory {
        override val pathName = "Music"
        override val extension = "umx"
    }

    /**
     * The directory the original ISO ships its 400-series system files in, merged into [System] on install.
     */
    object System400 : UtDirectory {
        override val pathName = "System400"
    }

    companion object {
        /**
         * The directories holding packages, in the order a redirect is probed for a package of an unknown extension.
         *
         * Built on first access rather than while the companion is initialized, because initializing one of the
         * directories initializes this interface and its companion first, and the list would then hold `null` for the
         * directory whose initialization it is part of.
         */
        val packages: List<UtDirectory> by lazy { listOf(System, Maps, Textures, Sounds, Music) }

        /**
         * Returns the directory a file of [extension] belongs in, comparing the extension ignoring case, or `null`
         * when no directory claims it.
         */
        fun of(extension: String): UtDirectory? = packages.firstOrNull { it.extension.equals(extension, ignoreCase = true) }
    }
}
