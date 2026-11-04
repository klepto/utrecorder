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
 * Every page read restores the withdrawn demos it lists. A tail scan collects the cap ids of every page read while it
 * runs, head pages included, and when it ends on a genuine end of the list, withdraws every stored demo it did not see.
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
     * Cap ids of the pages read since the running tail scan started, or `null` while no tail scan runs.
     */
    private var tailSeen: MutableSet<String>? = null

    /**
     * Time from which the next tail scan may start.
     */
    private var nextTailScan: TimeMark = timeSource.markNow()

    override suspend fun start() {
        while (isRunning()) {
            if (tailOffset == null && nextTailScan.hasPassedNow()) startTailScan()
            syncHead()
            syncTail()
            delay(config.utbtCooldown)
        }
    }

    /**
     * Stores the demos of the newest page that [repository] does not hold. If the read fails, logs it.
     */
    private suspend fun syncHead() {
        suspendCatching {
            val page = fetcher.fetch(0, config.utbtPageLimit)
            repository += page
            see(page)
        }.onFailure { log.warn(it) { "Could not sync the newest demos" } }
    }

    /**
     * Stores the demos of the next page of the tail scan that [repository] does not hold, if a tail scan runs. If the
     * read fails, logs it and keeps the offset of the page.
     */
    private suspend fun syncTail() {
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
        tailSeen = mutableSetOf()
        nextTailScan = timeSource.markNow() + config.utbtTailInterval
    }

    /**
     * Returns the offset of the page after [offset], after storing the demos of the page at [offset] that [repository]
     * does not hold. If the page is shorter than [ServerConfig.utbtPageLimit], ends the tail scan and returns `null`
     * instead.
     */
    private suspend fun syncTailPage(offset: Int): Int? {
        val page = fetcher.fetch(offset, config.utbtPageLimit)
        repository += page
        see(page)
        if (page.size >= config.utbtPageLimit) return offset + config.utbtTailStride
        endTailScan(offset, page.size)
        return null
    }

    /**
     * Restores the withdrawn demos of [page], and adds its cap ids to those the running tail scan saw, if one runs.
     */
    private suspend fun see(page: List<Demo>) {
        val capIds = page.map { it.capId }
        tailSeen?.addAll(capIds)
        repository.restore(capIds)
    }

    /**
     * Ends the tail scan whose last page, at [offset], holds [size] demos. If that page [isEndOfList], withdraws every
     * stored demo the scan did not see, and restores every one it saw. Otherwise logs a warning and withdraws nothing.
     */
    private suspend fun endTailScan(
        offset: Int,
        size: Int,
    ) {
        val seen = checkNotNull(tailSeen) { "No tail scan runs" }
        if (isEndOfList(offset, size)) {
            val before = repository.withdrawn()
            repository.withdrawAllExcept(seen)
            val after = repository.withdrawn()
            log.info { "Tail scan withdrew ${(after - before).size} demos and restored ${(before - after).size}" }
        } else {
            log.warn { "Tail scan ended on $size demos at offset $offset, which may not be the end of the list; withdrew nothing" }
        }
        tailSeen = null
    }

    /**
     * Returns `true` if the last page of a tail scan, at [offset] with [size] demos, is a genuine end of the list rather
     * than a truncated answer: it is not empty, and it either holds the [ServerConfig.utbtPageLimit] minus
     * [ServerConfig.utbtTailStride] demos it overlaps a full page before it with, or is the first page of the scan.
     */
    private fun isEndOfList(
        offset: Int,
        size: Int,
    ): Boolean = size > 0 && (size >= config.utbtPageLimit - config.utbtTailStride || offset == config.utbtTailStride)

    private companion object {
        val log = logger<DemoService>()
    }
}
