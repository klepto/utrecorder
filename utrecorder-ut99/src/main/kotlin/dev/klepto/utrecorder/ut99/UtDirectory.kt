package dev.klepto.utrecorder.ut99

import dev.klepto.utrecorder.common.io.PathName

/**
 * A directory of an Unreal Tournament installation, resolved against the installation root with the `get` operator.
 */
sealed interface UtDirectory : PathName {
    /**
     * The name of this directory on disk, the same as [pathName].
     */
    val name: String
        get() = pathName

    /**
     * The file extension of the packages this directory holds, without the leading dot.
     *
     * If this directory holds no packages, it is `null` instead.
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
     * The directory holding the 400-series system files of the original ISO.
     */
    object System400 : UtDirectory {
        override val pathName = "System400"
    }

    /**
     * The directory holding the help files of the original ISO.
     */
    object Help : UtDirectory {
        override val pathName = "Help"
    }

    /**
     * The directory holding the web administration files of the original ISO.
     */
    object Web : UtDirectory {
        override val pathName = "Web"
    }

    companion object {
        /**
         * The directories holding packages, in the order a package is looked up.
         */
        val PACKAGES: List<UtDirectory> by lazy { listOf(System, Maps, Textures, Sounds, Music) }

        /**
         * The extensions of the [PACKAGES] directories, in the same order.
         */
        val PACKAGE_EXTENSIONS: Set<String> by lazy { PACKAGES.mapNotNull { it.extension }.toSet() }

        /**
         * Returns the directory holding packages of [extension], comparing the extension ignoring case.
         *
         * If no directory holds packages of [extension], returns `null` instead.
         */
        fun of(extension: String): UtDirectory? = PACKAGES.firstOrNull { it.extension.equals(extension, ignoreCase = true) }
    }
}
