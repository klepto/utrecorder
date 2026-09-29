package dev.klepto.utrecorder.server

import dev.klepto.utrecorder.common.logger
import dev.klepto.utrecorder.common.suspendCatching
import dev.klepto.utrecorder.server.database.DocumentStore
import dev.klepto.utrecorder.server.demo.UtbtDemoFetcher
import dev.klepto.utrecorder.server.recording.RecordingQueue
import dev.klepto.utrecorder.server.recording.RecordingQueueService
import dev.klepto.utrecorder.server.recording.RecordingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
        DocumentStore.open(config.database) { store ->
            val queue = RecordingQueue(store)
            val services: List<Service> =
                listOf(
                    RecordingQueueService(UtbtDemoFetcher(config.utbt), queue, config.utbt),
                    RecordingService(queue, config = config),
                )
            runServices(services)
        }
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
     * When the JVM shuts down, the server is cancelled and the shutdown waits for it to finish.
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
