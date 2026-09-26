package dev.klepto.utrecorder.server.recording

import dev.klepto.utrecorder.server.Demo
import dev.klepto.utrecorder.server.Recording
import dev.klepto.utrecorder.server.RecordingLane
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * The in-memory FIFO of recordings waiting to be recorded, each with its [Demo].
 *
 * Both lanes take from the one queue, and each takes only recordings of its own [RecordingLane].
 */
class RecordingQueue {
    private val entries = MutableStateFlow<List<Pair<Demo, Recording>>>(emptyList())

    /**
     * Whether no entry is waiting in either lane.
     */
    val isEmpty: Boolean
        get() = entries.value.isEmpty()

    /**
     * Appends [entry] to the end of the queue.
     */
    fun enqueue(entry: Pair<Demo, Recording>) {
        entries.update { it + entry }
    }

    /**
     * Removes and returns the oldest entry whose recording is of [lane], suspending until one is enqueued.
     */
    suspend fun take(lane: RecordingLane): Pair<Demo, Recording> {
        while (true) {
            val current = entries.first { list -> list.any { it.second.lane == lane } }
            val index = current.indexOfFirst { it.second.lane == lane }
            val remaining = current.toMutableList().apply { removeAt(index) }
            if (entries.compareAndSet(current, remaining)) return current[index]
        }
    }
}
