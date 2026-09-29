package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.suspendCatching
import dev.klepto.utrecorder.server.Service
import dev.klepto.utrecorder.server.demo.Demo
import dev.klepto.utrecorder.server.demo.DemoFetcher
import dev.klepto.utrecorder.server.demo.UtbtConfig
import kotlinx.coroutines.delay
import kotlin.time.Instant

/**
 * [Service] that queues a recording of every demo of [fetcher] in [queue].
 *
 * Once per [UtbtConfig.cooldown], it reads two streams:
 * - The head: every demo added after the newest demo of [queue], stored once all of its pages are read. If [queue] is
 *   empty, only the newest page.
 * - The backlog: the page of demos added before the oldest demo of [queue]. A page that holds no new demo ends the
 *   backlog until the service starts again.
 *
 * A read that fails is logged, and stores nothing of its stream until the next cooldown.
 *
 * @property fetcher The source of the demos.
 * @property queue The queue the demos are stored in.
 * @property config The cooldown between two syncs.
 */
class RecordingQueueService(
    private val fetcher: DemoFetcher,
    private val queue: RecordingQueue,
    private val config: UtbtConfig = UtbtConfig(),
) : Service {
    override suspend fun start() {
        var backlogEnded = false
        while (true) {
            suspendCatching { syncHead() }
                .onFailure { log.warn(it) { "Could not sync the newest demos" } }
            if (!backlogEnded) {
                backlogEnded =
                    suspendCatching { syncBacklog() }
                        .onFailure { log.warn(it) { "Could not sync the older demos" } }
                        .getOrDefault(false)
            }
            delay(config.cooldown)
        }
    }

    /**
     * Stores the demos added after the newest demo of [queue], oldest first. If [queue] is empty, stores the newest
     * page instead.
     */
    private suspend fun syncHead() {
        val newest = queue.newest()
        val demos = if (newest == null) fetcher.fetch() else readAfter(newest)
        queue += demos.sortedBy { it.timestamp }
    }

    /**
     * Returns the demos added after [after] that [queue] does not hold, newest first. Reads pages until one holds no
     * such demo.
     */
    private suspend fun readAfter(after: Instant): List<Demo> {
        val fresh = mutableListOf<Demo>()
        do {
            val page = fetcher.fetch(fresh.lastOrNull()?.timestamp, after)
            val unseen = page.filter { it.capId !in queue && it !in fresh }
            fresh += unseen
        } while (unseen.isNotEmpty())
        return fresh
    }

    /**
     * Stores the page of demos added before the oldest demo of [queue], newest first. Returns `true` if the page holds
     * no demo that [queue] does not hold. If [queue] is empty, stores nothing and returns `false`.
     */
    private suspend fun syncBacklog(): Boolean {
        val oldest = queue.oldest() ?: return false
        val unseen = fetcher.fetch(before = oldest).filter { it.capId !in queue }
        queue += unseen
        return unseen.isEmpty()
    }

    private companion object {
        val log = logger<RecordingQueueService>()
    }
}
