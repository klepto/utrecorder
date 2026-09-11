package dev.klepto.utrecorder.ut99

import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.time.Duration

/**
 * What a recorded Unreal Tournament demo holds that a run needs before playing it: the map it opens, the packages it
 * loads and how long it plays.
 *
 * Use [read] to read one from a file or from its bytes.
 *
 * @property map The map the demo opens, such as `CTF-BT-II-CoZyliciouS`.
 * @property packages The packages the demo loads, in the order the demo names them. A package named more than once
 * appears once, as its first entry, with names compared ignoring case.
 * @property duration The demo time of the last record. It can overshoot the played length by a fraction of a second.
 */
data class UtDemo(
    val map: String,
    val packages: List<UtDemoPackage>,
    val duration: Duration,
) {
    companion object {
        /**
         * Reads the demo file at the given [path].
         */
        fun read(path: Path): UtDemo = read(path.readBytes())

        /**
         * Reads the demo held in [bytes]. Reading stops at the first record that does not decode and keeps the records
         * before it. Throws [IllegalStateException] if no record names the map.
         */
        fun read(bytes: ByteArray): UtDemo = UtDemoReader.read(bytes)
    }
}

/**
 * A package a demo loads, as the demo names it.
 *
 * @property name The name of the package, such as `Engine`.
 * @property guid The GUID of the package version the demo was recorded against, as the demo writes it.
 * @property fileName The file the package was loaded from, either a package file name or a cache name of the form
 * `<GUID>.uxx`.
 */
data class UtDemoPackage(
    val name: String,
    val guid: String,
    val fileName: String,
)
