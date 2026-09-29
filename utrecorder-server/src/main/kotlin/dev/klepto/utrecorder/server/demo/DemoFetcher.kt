package dev.klepto.utrecorder.server.demo

import kotlin.time.Instant

/**
 * Source of the demos to record, read one page at a time.
 */
interface DemoFetcher {
    /**
     * Returns one page of the demos added before [before] and after [after], newest first. Both bounds are exclusive,
     * and a bound that is `null` does not limit the page. Throws if the page cannot be read.
     */
    suspend fun fetch(
        before: Instant? = null,
        after: Instant? = null,
    ): List<Demo>
}
