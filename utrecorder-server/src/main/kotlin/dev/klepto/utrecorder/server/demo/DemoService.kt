package dev.klepto.utrecorder.server.demo

import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.suspendCatching
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.Service
import kotlinx.coroutines.delay
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * [Service] that stores every demo of [fetcher] in [repository].
 *
 * Once per [ServerConfig.utbtCooldown], it reads up to two pages of [ServerConfig.utbtPageLimit] demos, and stores the
 * demos of each that [repository] does not hold:
 * - The head: the newest page.
 * - The tail: while a tail scan runs, its next page. A tail scan reads the page at [ServerConfig.utbtTailStride], then
 *   one page per [ServerConfig.utbtTailStride] further, and ends after a page shorter than [ServerConfig.utbtPageLimit].
 *
 * A tail scan starts when the service starts, and again [ServerConfig.utbtTailInterval] after the previous one started.
 * If the previous one still runs at that time, the next one starts once it ends.
 *
 * A read that fails is logged and stores nothing. A failed tail page is read again at the next cooldown.
 *
 * @property fetcher The source of the demos.
 * @property repository The repository the demos are stored in.
 * @property config The cooldown between two reads, the page limit, and the stride and interval of the tail scan.
 * @property timeSource The source of the time measured from the start of a tail scan to the start of the next.
 */
class DemoService(
    private val fetcher: DemoFetcher,
    private val repository: DemoRepository,
    private val config: ServerConfig = ServerConfig(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : Service {
    /**
     * Offset of the next page of the running tail scan, or `null` while no tail scan runs.
     */
    private var tailOffset: Int? = null

    /**
     * Time from which the next tail scan may start.
     */
    private var nextTailScan: TimeMark = timeSource.markNow()

    override suspend fun start() {
        while (isRunning()) {
            syncHead()
            syncTail()
            delay(config.utbtCooldown)
        }
    }

    /**
     * Stores the demos of the newest page that [repository] does not hold. If the read fails, logs it.
     */
    private suspend fun syncHead() {
        suspendCatching { repository += fetcher.fetch(0, config.utbtPageLimit) }
            .onFailure { log.warn(it) { "Could not sync the newest demos" } }
    }

    /**
     * Stores the demos of the next page of the tail scan that [repository] does not hold. If no tail scan runs, starts
     * one first once [nextTailScan] has passed. If the read fails, logs it and keeps the offset of the page.
     */
    private suspend fun syncTail() {
        if (tailOffset == null && nextTailScan.hasPassedNow()) startTailScan()
        val offset = tailOffset ?: return
        suspendCatching { syncTailPage(offset) }
            .onSuccess { tailOffset = it }
            .onFailure { log.warn(it) { "Could not sync the demos at offset $offset" } }
    }

    /**
     * Starts a tail scan at [ServerConfig.utbtTailStride], and sets the next one [ServerConfig.utbtTailInterval] from
     * now.
     */
    private fun startTailScan() {
        tailOffset = config.utbtTailStride
        nextTailScan = timeSource.markNow() + config.utbtTailInterval
    }

    /**
     * Returns the offset of the page after [offset], after storing the demos of the page at [offset] that [repository]
     * does not hold. If the page is shorter than [ServerConfig.utbtPageLimit], returns `null` instead.
     */
    private suspend fun syncTailPage(offset: Int): Int? {
        val page = fetcher.fetch(offset, config.utbtPageLimit)
        repository += page
        return (offset + config.utbtTailStride).takeUnless { page.size < config.utbtPageLimit }
    }

    private companion object {
        val log = logger<DemoService>()
    }
}
