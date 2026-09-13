package dev.klepto.utrecorder.ut99.demo

import dev.klepto.utrecorder.ut99.UtPackage
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.time.Duration

/**
 * Parsed fields of an Unreal Tournament `.dem` file.
 *
 * Use [read] to parse a demo from a [Path] or from its bytes.
 *
 * @property map The map the demo opens, such as `CTF-BT-II-CoZyliciouS`.
 * @property packages The packages the demo loads, in the order the demo names them, with the level package first. A
 * package named more than once appears once, as its first entry, with names compared ignoring case.
 * @property duration The demo time of the last record. It can overshoot the played length by a fraction of a second.
 * @property cap The cap that ends the run the demo records, or `null` when the demo holds no cap.
 */
data class UtDemo(
    val map: String,
    val packages: List<UtPackage>,
    val duration: Duration,
    val cap: UtDemoCap?,
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
