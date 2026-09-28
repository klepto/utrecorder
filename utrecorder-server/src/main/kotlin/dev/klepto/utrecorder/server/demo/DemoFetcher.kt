package dev.klepto.utrecorder.server.demo

import kotlinx.coroutines.flow.Flow

/**
 * Source of the demos to record.
 */
interface DemoFetcher {
    /**
     * Returns the demos to record, each emitted at most once per collection.
     *
     * The flow never completes. It emits the next demo only when its collector asks for one. Each collection emits every
     * demo, whatever other collections have emitted.
     */
    suspend fun fetch(): Flow<Demo>
}
