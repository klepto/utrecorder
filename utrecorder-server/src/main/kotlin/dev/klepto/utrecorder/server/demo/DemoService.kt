package dev.klepto.utrecorder.server.demo

import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.suspendCatching
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.Service
import kotlinx.coroutines.delay
import kotlin.time.Instant

/**
 * [Service] that stores every demo of [fetcher] in [repository].
 *
 * Once per [ServerConfig.utbtCooldown], it reads two streams:
 * - The head: every demo added after the newest demo of [repository], stored once all of its pages are read. If
 *   [repository] is empty, only the newest page.
 * - The backlog: the page of demos added before the oldest demo of [repository]. A page that holds no new demo ends
 *   the backlog until the service starts again.
 *
 * A read that fails is logged, and stores nothing of its stream until the next cooldown.
 *
 * @property fetcher The source of the demos.
 * @property repository The repository the demos are stored in.
 * @property config The cooldown between two syncs.
 */
class DemoService(
    private val fetcher: DemoFetcher,
    private val repository: DemoRepository,
    private val config: ServerConfig = ServerConfig(),
) : Service {
    override suspend fun start() {
        var backlogEnded = false
        while (isRunning()) {
            suspendCatching { syncHead() }
                .onFailure { log.warn(it) { "Could not sync the newest demos" } }
            if (!backlogEnded) {
                backlogEnded =
                    suspendCatching { syncBacklog() }
                        .onFailure { log.warn(it) { "Could not sync the older demos" } }
                        .getOrDefault(false)
            }
            delay(config.utbtCooldown)
        }
    }

    /**
     * Stores the demos added after the newest demo of [repository], oldest first. If [repository] is empty, stores the
     * newest page instead.
     */
    private suspend fun syncHead() {
        val newest = repository.newest()
        val demos = if (newest == null) fetcher.fetch() else readAfter(newest)
        repository += demos.sortedBy { it.timestamp }
    }

    /**
     * Returns the demos added after [after] that [repository] does not hold, newest first. Reads pages until one holds
     * no such demo.
     */
    private suspend fun readAfter(after: Instant): List<Demo> {
        val fresh = mutableListOf<Demo>()
        do {
            val page = fetcher.fetch(fresh.lastOrNull()?.timestamp, after)
            val unseen = page.filter { it.capId !in repository && it !in fresh }
            fresh += unseen
        } while (unseen.isNotEmpty())
        return fresh
    }

    /**
     * Stores the page of demos added before the oldest demo of [repository], newest first. Returns `true` if the page
     * holds no demo that [repository] does not hold. If [repository] is empty, stores nothing and returns `false`.
     */
    private suspend fun syncBacklog(): Boolean {
        val oldest = repository.oldest() ?: return false
        val unseen = fetcher.fetch(before = oldest).filter { it.capId !in repository }
        repository += unseen
        return unseen.isEmpty()
    }

    private companion object {
        val log = logger<DemoService>()
    }
}
