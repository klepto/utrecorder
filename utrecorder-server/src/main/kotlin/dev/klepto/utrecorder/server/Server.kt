package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.suspendCatching
import dev.klepto.utrecorder.server.cache.CacheService
import dev.klepto.utrecorder.server.database.SqliteDocumentStore
import dev.klepto.utrecorder.server.demo.DemoFetchService
import dev.klepto.utrecorder.server.demo.UtbtDemoFetcher
import dev.klepto.utrecorder.server.http.HttpService
import dev.klepto.utrecorder.server.recording.RecordingJobs
import dev.klepto.utrecorder.server.recording.RecordingRepository
import dev.klepto.utrecorder.server.recording.RecordingService
import dev.klepto.utrecorder.server.upload.ArchiveMediaUploader
import dev.klepto.utrecorder.server.upload.MediaUploader
import dev.klepto.utrecorder.server.upload.UploadService
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
     * store at [ServerConfig.database]. Once every service has ended, stops each service in the reverse order of
     * starting.
     *
     * A service that throws cancels every other service, and the exception is rethrown after the services are stopped.
     */
    suspend fun run(config: ServerConfig = ServerConfig()) {
        log.info { "Starting the server against ${config.utbt.apiUrl}" }
        SqliteDocumentStore.open(config.database) { store ->
            val repository = RecordingRepository(store, config.recording)
            val jobs = RecordingJobs()
            val uploader = uploader(config)
            val cache = uploader?.let { CacheService(repository, it, config) }
            val services: List<Service> =
                listOfNotNull(
                    DemoFetchService(UtbtDemoFetcher(config.utbt), repository, config.utbt),
                    RecordingService(repository, config = config, jobs = jobs),
                    uploader?.let { UploadService(repository, it, config) },
                    cache,
                    HttpService(repository, jobs, cache, config),
                )
            runServices(services)
        }
    }

    /**
     * Returns the Internet Archive uploader of [config]. If the Archive keys of [config] are not both configured, logs a
     * warning and returns `null` instead.
     */
    private fun uploader(config: ServerConfig): MediaUploader? {
        if (config.archive.accessKey == null || config.archive.secretKey == null) {
            log.warn { "Not uploading or evicting recordings, the Internet Archive keys are not configured" }
            return null
        }
        return ArchiveMediaUploader(config.archive)
    }

    /**
     * Runs each of [services] in its own coroutine until it is cancelled. Once every service has ended, stops each
     * service in the reverse order of starting.
     */
    private suspend fun runServices(services: List<Service>) {
        try {
            coroutineScope {
                services.forEach { launch { it.start() } }
            }
        } finally {
            withContext(NonCancellable) { stop(services) }
        }
    }

    /**
     * Stops each of [services] in reverse order. A service whose [Service.stop] throws is logged, and the rest are
     * still stopped.
     */
    private suspend fun stop(services: List<Service>) {
        services.asReversed().forEach { service ->
            suspendCatching { service.stop() }
                .onFailure { log.error(it) { "Could not stop the service ${service::class.simpleName}" } }
        }
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
