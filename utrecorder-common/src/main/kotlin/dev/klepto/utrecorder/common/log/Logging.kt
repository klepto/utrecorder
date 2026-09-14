package dev.klepto.utrecorder.common.log

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * A logger named after the class [T].
 */
inline fun <reified T : Any> logger(): KLogger = KotlinLogging.logger(T::class.java.name)
