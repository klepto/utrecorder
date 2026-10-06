package dev.klepto.utrecorder.server.demo

/**
 * Source of the demos to record, read one page at a time.
 */
interface DemoFetcher {
    /**
     * Returns one page of at most [limit] demos, newest first, after skipping the [offset] newest demos. Throws if the
     * page cannot be read.
     */
    suspend fun fetch(
        offset: Int,
        limit: Int,
    ): List<Demo>
}
