package dev.klepto.utrecorder.common

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Returns a logger named after the fully qualified name of the class [T].
 */
inline fun <reified T : Any> logger(): KLogger = KotlinLogging.logger(T::class.java.name)
