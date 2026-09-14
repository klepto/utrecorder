package dev.klepto.utrecorder.common.config

import kotlin.time.Duration

/**
 * A reader of configuration values, backed by a lookup of properties and a lookup of environment variables.
 *
 * Use [DEFAULT] for the source that reads the system properties and the environment of the current process.
 *
 * @property prefix The prefix every key is read under.
 * @property properties The lookup of property values by name.
 * @property environment The lookup of environment variable values by name.
 */
class PropertySource(
    private val prefix: String = APPLICATION_PREFIX,
    private val properties: (String) -> String? = System::getProperty,
    private val environment: (String) -> String? = System::getenv,
) {
    /**
     * The value of the given [key], or `null` if neither the properties nor the environment hold one.
     *
     * The key is read as the property `<prefix>.<key>`, then as the environment variable `<PREFIX>_<KEY>` with every
     * dot replaced by an underscore. An empty value reads as missing.
     */
    operator fun get(key: String): String? =
        systemProperty("$prefix.$key")
            ?: environmentVariable("${prefix.uppercase()}_${key.uppercase().replace('.', '_')}")

    /**
     * The value of the given [key] read by [mapper], or `null` if it holds none.
     */
    fun <T> get(
        key: String,
        mapper: (String) -> T,
    ): T? = get(key)?.let(mapper)

    /**
     * The comma-separated values of the given [key], trimmed and without the empty ones, or `null` if it holds none.
     */
    fun list(key: String): List<String>? =
        get(key)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }

    /**
     * The duration the given [key] holds in the format [Duration.parse] reads, or `null` if it holds none.
     *
     * @throws IllegalArgumentException if the value is not a duration.
     */
    fun duration(key: String): Duration? = get(key, Duration::parse)

    /**
     * The value of the unprefixed property [name], or `null` if it holds none.
     */
    fun systemProperty(name: String): String? = properties(name)?.takeIf { it.isNotEmpty() }

    /**
     * The value of the unprefixed environment [variable], or `null` if it holds none.
     */
    fun environmentVariable(variable: String): String? = environment(variable)?.takeIf { it.isNotEmpty() }

    companion object {
        /**
         * The prefix keys of this application take.
         */
        const val APPLICATION_PREFIX = "utrecorder"

        /**
         * The source a configuration that names none reads from.
         */
        val DEFAULT = PropertySource()
    }
}
