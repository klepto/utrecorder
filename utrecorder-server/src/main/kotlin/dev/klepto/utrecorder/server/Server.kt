package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.server.cache.CacheService
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import dev.klepto.utrecorder.server.demo.DemoRepository
import dev.klepto.utrecorder.server.demo.DemoService
import dev.klepto.utrecorder.server.demo.UtbtDemoFetcher
import dev.klepto.utrecorder.server.http.HttpService
import dev.klepto.utrecorder.server.http.JobEndpoint
import dev.klepto.utrecorder.server.http.QueueEndpoint
import dev.klepto.utrecorder.server.http.RecordingEndpoint
import dev.klepto.utrecorder.server.http.StatisticsEndpoint
import dev.klepto.utrecorder.server.http.VideoEndpoint
import dev.klepto.utrecorder.server.http.WebEndpoint
import dev.klepto.utrecorder.server.recording.DefaultRecorderProvider
import dev.klepto.utrecorder.server.recording.RecordingJobs
import dev.klepto.utrecorder.server.recording.RecordingQueue
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingService
import dev.klepto.utrecorder.server.statistics.StatisticsReport
import dev.klepto.utrecorder.server.upload.ArchiveMediaUploader
import dev.klepto.utrecorder.server.upload.MediaUploader
import dev.klepto.utrecorder.server.upload.UploadService
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

/**
 * Entry point of the recording backend server.
 *
 * Run it through [main], or call [run] with a [ServerConfig].
 */
object Server {
    private val log = logger<Server>()

    /**
     * Runs every service of the server from [config], each in its own coroutine, until it is cancelled, on the document
     * store at [ServerConfig.database].
     *
     * A service that throws cancels every other service, and the exception is rethrown.
     */
    suspend fun run(config: ServerConfig = ServerConfig()) {
        log.info { "Starting the server against ${config.utbtApiUrl}" }
        SqliteDocumentStore.open(config.database) { store ->
            val demos = DemoRepository(store)
            val repository = RecordingRepository(store, demos)
            val queue = RecordingQueue(demos, repository, config)
            val jobs = RecordingJobs()
            val uploader = uploader(config)
            val cache = CacheService(repository, uploader, config)
            val recorder = DefaultRecorderProvider(config.recorder)
            val services: List<Service> =
                listOfNotNull(
                    DemoService(UtbtDemoFetcher(config), demos, config),
                    queue,
                    RecordingService(repository, queue, listOf(recorder), config, jobs),
                    uploader?.let { UploadService(repository, it, config) },
                    cache,
                    HttpService(
                        listOf(
                            VideoEndpoint(repository, cache, config),
                            RecordingEndpoint(repository, config),
                            JobEndpoint(jobs),
                            QueueEndpoint(queue),
                            StatisticsEndpoint(StatisticsReport(demos, repository, queue, jobs, cache, config)),
                            WebEndpoint(),
                        ),
                        config,
                    ),
                )
            coroutineScope {
                services.forEach { launch { it.start() } }
            }
        }
    }

    /**
     * Returns the Internet Archive uploader of [config]. If the Archive keys of [config] are not both configured, logs a
     * warning and returns `null` instead.
     */
    private fun uploader(config: ServerConfig): MediaUploader? {
        if (config.archiveAccessKey == null || config.archiveSecretKey == null) {
            log.warn { "Not uploading recordings, the Internet Archive keys are not configured" }
            return null
        }
        return ArchiveMediaUploader(config)
    }

    /**
     * Runs the server from the configuration of the current process.
     *
     * When the JVM shuts down, the server is cancelled, the shutdown waits for it to finish, and the stop is logged.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val job = Job()
        val mainThread = Thread.currentThread()
        Runtime.getRuntime().addShutdownHook(
            Thread {
                job.cancel()
                mainThread.join()
            },
        )
        try {
            runBlocking(job) {
                run()
            }
        } catch (e: CancellationException) {
            log.info { "Stopped the server: ${e.message}" }
        }
    }
}
