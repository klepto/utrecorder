package dev.klepto.utrecorder.server.source

import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.server.Demo
import dev.klepto.utrecorder.server.DemoStore
import dev.klepto.utrecorder.server.Recording
import dev.klepto.utrecorder.server.RecordingLane
import dev.klepto.utrecorder.server.RecordingState
import dev.klepto.utrecorder.server.ServerConfig
import dev.klepto.utrecorder.server.recording.RecordingQueue
import dev.klepto.utrecorder.ut99.demo.UtDemo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/**
 * Finds caps at a [DemoSource], stores each as a [Demo] with a [Recording], and enqueues the recording.
 *
 * Two loops find caps: [poll] takes the caps added since the newest known one, and [backfill] walks the older ones
 * while the server is idle. Each cap is handled once: its demo is downloaded into memory, read with [UtDemo.read] for
 * its map and duration, stored, and enqueued in the lane its duration picks. A demo the source does not serve, or that
 * does not parse, is stored with a [RecordingState.FAILED] recording and not enqueued. A failed download stores
 * nothing, so a later pass retries it.
 *
 * @property source The source caps and demos are read from.
 * @property store The store every handled cap is written to.
 * @property queue The queue every stored recording is enqueued in.
 * @property uploaderIsEmpty Returns whether the uploader has no recording waiting to be uploaded.
 * @property config The poll interval, the backfill cooldown and the long lane threshold.
 * @param demos The demos stored when the server started.
 */
class DemoPoller(
    private val source: DemoSource,
    private val store: DemoStore,
    private val queue: RecordingQueue,
    private val uploaderIsEmpty: () -> Boolean,
    private val config: ServerConfig = ServerConfig(),
    demos: List<Demo> = emptyList(),
) {
    private val mutex = Mutex()
    private val known = demos.mapTo(mutableSetOf()) { it.sourceId }
    private var backfillOffset = demos.size
    private var firstPoll = demos.isEmpty()

    /**
     * Runs [poll] every [ServerConfig.pollInterval], forever. A failed pass is logged and retried at the next one.
     */
    suspend fun runPolling(): Nothing {
        while (true) {
            try {
                poll()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                log.warn(failure) { "Demo poll failed, retrying in ${config.pollInterval}" }
            }
            delay(config.pollInterval)
        }
    }

    /**
     * Handles, oldest first, every cap added since the newest known one.
     *
     * Reads pages from offset `0` until a page holds a known cap or is empty. When no demo was stored at startup, the
     * first pass takes only the newest cap and leaves the older ones to [backfill]. A pass stops at the first failed
     * download, so the next pass retries it before any newer cap.
     */
    suspend fun poll() {
        val unseen = mutableListOf<Cap>()
        var offset = 0
        while (true) {
            val page = source.caps(offset)
            val fresh = page.takeWhile { it.id !in known }
            if (firstPoll) {
                unseen += fresh.take(1)
                break
            }
            unseen += fresh
            if (page.isEmpty() || fresh.size < page.size) break
            offset += page.size
        }
        for (cap in unseen.asReversed()) {
            if (!handle(cap)) return
        }
        firstPoll = false
    }

    /**
     * Walks the caps older than those known at startup, newest first, until a page comes back empty.
     *
     * Starts at the number of demos stored at startup and skips every known cap. Before each cap it waits until both
     * the [queue] and the uploader are empty, and after each it waits [ServerConfig.backfillCooldown]. A failed page
     * read or download is logged and retried after the cooldown.
     */
    suspend fun backfill() {
        while (true) {
            val page =
                retrying("Backfill page read at offset $backfillOffset") { source.caps(backfillOffset) }
            if (page.isEmpty()) return
            for (cap in page) {
                while (cap.id !in known) {
                    awaitIdle()
                    val handled = handle(cap)
                    delay(config.backfillCooldown)
                    if (handled) break
                }
            }
            backfillOffset += page.size
        }
    }

    private suspend fun awaitIdle() {
        while (!queue.isEmpty || !uploaderIsEmpty()) delay(IDLE_CHECK_INTERVAL)
    }

    private suspend fun <T> retrying(
        action: String,
        block: suspend () -> T,
    ): T {
        while (true) {
            try {
                return block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                log.warn(failure) { "$action failed, retrying in ${config.backfillCooldown}" }
                delay(config.backfillCooldown)
            }
        }
    }

    /**
     * Stores and enqueues [cap] unless it is known. Returns `false` if its download failed and nothing was stored.
     */
    private suspend fun handle(cap: Cap): Boolean =
        mutex.withLock {
            if (cap.id in known) return true
            val bytes =
                try {
                    source.demo(cap.id)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    log.warn(failure) { "Download of demo ${cap.id} failed, retrying later" }
                    return false
                }
            val parsed =
                bytes?.let {
                    try {
                        Result.success(UtDemo.read(it))
                    } catch (failure: Exception) {
                        Result.failure(failure)
                    }
                } ?: Result.failure(IllegalStateException("The demo source does not serve demo ${cap.id}"))
            val demo = parsed.getOrNull()
            val stored =
                store.insert(
                    Demo(
                        id = 0,
                        sourceId = cap.id,
                        map = demo?.map,
                        player = cap.alias,
                        capTime = cap.capTime,
                        duration = demo?.duration,
                        addedAt = cap.added,
                        recordingId = 0,
                    ),
                    Recording(
                        lane = if (demo != null && demo.duration >= config.longThreshold) RecordingLane.LONG else RecordingLane.SHORT,
                        state = if (demo != null) RecordingState.PENDING else RecordingState.FAILED,
                        error = parsed.exceptionOrNull()?.let { it.message ?: it.toString() },
                    ),
                )
            known += cap.id
            if (demo != null) {
                queue.enqueue(stored)
            } else {
                log.warn(parsed.exceptionOrNull()) { "Demo ${cap.id} stored as failed" }
            }
            true
        }

    private companion object {
        val log = logger<DemoPoller>()

        /** The time between two checks of whether the queue and the uploader are empty. */
        val IDLE_CHECK_INTERVAL = 10.seconds
    }
}
