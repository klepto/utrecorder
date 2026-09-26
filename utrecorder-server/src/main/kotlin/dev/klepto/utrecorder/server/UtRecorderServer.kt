package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.core.Recorder
import dev.klepto.utrecorder.core.RecorderProgress
import dev.klepto.utrecorder.server.archive.InternetArchive
import dev.klepto.utrecorder.server.archive.Uploader
import dev.klepto.utrecorder.server.cache.CacheEvictor
import dev.klepto.utrecorder.server.cache.RecordingCache
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.demo.DemoFetcher
import dev.klepto.utrecorder.server.demo.UtbtDemoFetcher
import dev.klepto.utrecorder.server.http.serveRoute
import dev.klepto.utrecorder.server.recording.Lane
import dev.klepto.utrecorder.server.recording.Recording
import dev.klepto.utrecorder.server.recording.RecordingLane
import dev.klepto.utrecorder.server.recording.RecordingQueue
import dev.klepto.utrecorder.server.recording.RecordingState
import io.ktor.client.engine.HttpClientEngine
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.exists
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

/**
 * Entry point of the recording backend server.
 *
 * Run it through [main], or call [run] with a [ServerConfig] and the seams a test replaces.
 */
object UtRecorderServer {
    private val log = logger<UtRecorderServer>()

    /**
     * Runs the server from [config] until cancelled.
     *
     * Restores the state stored in [ServerConfig.database]. A recording in [RecordingState.RECORDING] is reset to
     * [RecordingState.PENDING], and a recording whose mp4 is missing from the cache directory loses its
     * [Recording.file]. Both changes are written back. Then every pending recording is enqueued, oldest demo first, every
     * recorded one is sent to the uploader. Then runs both lanes, the uploader, the demo fetcher, the cache's
     * re-cache loop, the evictor, the cleanup loop and the HTTP server on [ServerConfig.port], until cancelled.
     *
     * @param config The settings of the server.
     * @param engine The HTTP client engine UTBT and the Internet Archive are reached through.
     * @param record Returns the mp4 recorded from the demo at the given URL, reporting progress to the given listener.
     */
    suspend fun run(
        config: ServerConfig = ServerConfig(),
        engine: HttpClientEngine = ClientCIO.create(),
        record: suspend (url: String, onProgress: (RecorderProgress) -> Unit) -> Path = { url, onProgress ->
            Recorder(directories = config.directories).record(url, onProgress = onProgress)
        },
    ) {
        val store = DocumentStore.open(config.database)
        try {
            serve(config, store, engine, record)
        } finally {
            withContext(NonCancellable) { store.close() }
        }
    }

    private suspend fun serve(
        config: ServerConfig,
        store: DocumentStore,
        engine: HttpClientEngine,
        record: suspend (url: String, onProgress: (RecorderProgress) -> Unit) -> Path,
    ) {
        val demos = store.collection("demos", Demo.serializer(), Demo::id)
        val recordings = store.collection("recordings", Recording.serializer(), Recording::demoId)
        val cleanups = store.collection("cleanups", Cleanup.serializer()) { it.path.toString() }
        val storedDemos = demos.all()
        val storedRecordings = recordings.all().map { recover(it, recordings, config.cache) }
        val archive = InternetArchive(config.archive, engine)
        val queue = RecordingQueue()
        val cleanup = CleanupService(cleanups)
        val uploader = Uploader(archive, recordings, config)
        val cache = RecordingCache(recordings, archive, config)
        val evictor = CacheEvictor(recordings, cleanup, config)
        val fetcher: DemoFetcher = UtbtDemoFetcher(demos, recordings, queue, { uploader.isEmpty }, config, storedDemos, engine)
        val lanes =
            RecordingLane.entries.map { lane ->
                Lane(lane, queue, recordings, cleanup, archive, uploader::send, config, record)
            }
        restore(storedDemos, storedRecordings, queue, uploader)
        log.info { "Restored ${storedDemos.size} demos, starting the server on port ${config.port}" }
        coroutineScope {
            lanes.forEach { lane -> launch { lane.run() } }
            launch { uploader.run() }
            launch { fetcher.run() }
            launch { cache.run() }
            launch { evictor.loop() }
            launch { cleanup.loop() }
            embeddedServer(ServerCIO, port = config.port) { serveRoute(recordings, cache, lanes) }.startSuspend(wait = true)
        }
    }

    private suspend fun recover(
        recording: Recording,
        recordings: DocumentStore.Collection<Recording>,
        cache: Path,
    ): Recording =
        withContext(Dispatchers.IO) { recovered(recording, cache) }
            .also { if (it != recording) recordings.put(it) }

    private fun recovered(
        recording: Recording,
        cache: Path,
    ): Recording =
        recording
            .let { if (it.state == RecordingState.RECORDING) it.copy(state = RecordingState.PENDING) else it }
            .let { if (it.file != null && !cache.resolve(it.file).exists()) it.copy(file = null) else it }

    private suspend fun restore(
        demos: List<Demo>,
        recordings: List<Recording>,
        queue: RecordingQueue,
        uploader: Uploader,
    ) {
        val recordingsById = recordings.associateBy { it.demoId }
        demos
            .sortedBy { it.addedAt }
            .forEach { demo ->
                val recording = recordingsById.getValue(demo.id)
                when (recording.state) {
                    RecordingState.PENDING -> queue.enqueue(demo to recording)
                    RecordingState.RECORDED -> uploader.send(demo, recording)
                    else -> Unit
                }
            }
    }

    /**
     * Runs the server from the configuration of the current process.
     *
     * When the JVM shuts down, the server is cancelled and the shutdown waits for it to finish, so a running
     * recording is restored as pending at the next start.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val job = Job()
        Runtime.getRuntime().addShutdownHook(
            Thread {
                job.cancel()
                runBlocking { job.join() }
            },
        )
        runBlocking(job) {
            run()
        }
    }
}
